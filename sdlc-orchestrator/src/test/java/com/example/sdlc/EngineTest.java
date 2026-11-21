package com.example.sdlc;

import com.example.sdlc.agents.Agent;
import com.example.sdlc.agents.AgentContext;
import com.example.sdlc.agents.AgentResult;
import com.example.sdlc.agents.Catalog;
import com.example.sdlc.approval.ApprovalService;
import com.example.sdlc.approval.ApproverRegistry;
import com.example.sdlc.audit.AuditLog;
import com.example.sdlc.engine.Engine;
import com.example.sdlc.gates.Gates;
import com.example.sdlc.json.Json;
import com.example.sdlc.llm.LlmClient;
import com.example.sdlc.llm.OfflineLlm;
import com.example.sdlc.model.ApprovalRequest;
import com.example.sdlc.model.FileChange;
import com.example.sdlc.model.NodeState;
import com.example.sdlc.model.RunState;
import com.example.sdlc.model.RunStatus;
import com.example.sdlc.model.StageDef;
import com.example.sdlc.policy.PolicyEngine;
import com.example.sdlc.report.Metrics;
import com.example.sdlc.store.ArtifactStore;
import com.example.sdlc.store.RunStore;
import com.example.sdlc.tools.TestRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Orchestration guarantees, verified with scripted fake agents (no LLM, no Maven). */
class EngineTest {

    @FunctionalInterface
    interface Fn {
        AgentResult apply(AgentContext ctx) throws Exception;
    }

    private Path tmp;
    private RunStore store;
    private final PolicyEngine policy = PolicyEngine.defaults();
    private final List<String> console = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        tmp = Files.createTempDirectory("engine");
        store = new RunStore(tmp.resolve("runs"), Clock.systemUTC());
    }

    // ---------------------------------------------------------------- helpers

    private void agent(String name, Fn fn) {
        calls.put(name, new AtomicInteger());
        agents.put(name, new Agent() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AgentResult run(AgentContext ctx) throws Exception {
                calls.get(name).incrementAndGet();
                return fn.apply(ctx);
            }
        });
    }

    /** Stage whose agent has the same name and which must produce "<id>-out". */
    private static StageDef st(String id, String... deps) {
        StageDef s = new StageDef();
        s.id = id;
        s.agent = id;
        s.dependsOn = new ArrayList<>(List.of(deps));
        s.produces = new ArrayList<>(List.of(id + "-out"));
        s.exitGates = new ArrayList<>(List.of("required-outputs"));
        s.backoffMs = 10;
        for (String d : deps) {
            s.consumes.add(d + "-out");
        }
        return s;
    }

    private static AgentResult out(String stage, String content) {
        return new AgentResult().output(stage + "-out", content);
    }

    private Engine engine() {
        return engine(new OfflineLlm());
    }

    private Engine engine(LlmClient llm) {
        return new Engine(store, agents, new Gates(policy), policy, llm, Catalog.load(), new TestRunner(),
                Clock.systemUTC(), 4, console::add);
    }

    /** A model that is "available" but whose output never validates: forces the fallback path. */
    private static final class UnreliableLlm implements LlmClient {
        @Override
        public java.util.Optional<String> complete(String system, String user, int maxTokens) {
            return java.util.Optional.of("I'm sorry, here is some prose instead of the JSON you asked for.");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String describe() {
            return "unreliable-test-model";
        }
    }

    private RunState newRun(StageDef... stages) throws Exception {
        RunState r = new RunState();
        r.scenario = "test";
        r.runId = store.newRunId("t");
        r.initiator = "manas";
        r.workflow = "test";
        r.stages = new ArrayList<>(List.of(stages));
        Path ws = tmp.resolve("ws-" + r.runId);
        Files.createDirectories(ws);
        r.config.put("workspace", ws.toString());
        return r;
    }

    private Path ws(RunState r) {
        return Path.of(r.cfg("workspace", ""));
    }

    private List<Map<String, Object>> audit(RunState r) {
        return AuditLog.forRun(store.runDir(r.runId), r.runId, Clock.systemUTC()).readAll();
    }

    private long events(RunState r, String type) {
        return audit(r).stream().filter(e -> type.equals(e.get("type"))).count();
    }

    // ---------------------------------------------------------------- tests

    @Test
    void parallelBranchesRunConcurrentlyAndJoinBeforeDownstream() throws Exception {
        Map<String, long[]> spans = new ConcurrentHashMap<>();
        for (String id : List.of("a", "b", "c", "d")) {
            agent(id, ctx -> {
                long start = System.nanoTime();
                if (id.equals("b") || id.equals("c")) {
                    Thread.sleep(200);
                }
                spans.put(id, new long[]{start, System.nanoTime()});
                return out(id, id);
            });
        }
        RunState run = engine().start(newRun(st("a"), st("b", "a"), st("c", "a"), st("d", "b", "c")), "req");
        assertEquals(RunStatus.SUCCEEDED, run.status);
        long[] b = spans.get("b");
        long[] c = spans.get("c");
        assertTrue(Math.max(b[0], c[0]) < Math.min(b[1], c[1]), "b and c overlap in time (parallel)");
        assertTrue(spans.get("d")[0] >= Math.max(b[1], c[1]), "d starts only after both branches finish (join)");
        assertTrue(audit(run).size() > 10);
        assertTrue(AuditLog.forRun(store.runDir(run.runId), run.runId, Clock.systemUTC()).verify().ok());
    }

    @Test
    void transientFailureIsRetriedWithFeedbackThenSucceeds() throws Exception {
        List<List<String>> feedbackSeen = new ArrayList<>();
        agent("flaky", ctx -> {
            feedbackSeen.add(ctx.feedback());
            if (ctx.attempt() == 1) {
                throw new IllegalStateException("downstream timeout");
            }
            return out("flaky", "ok");
        });
        StageDef s = st("flaky");
        s.maxAttempts = 3;
        RunState run = engine().start(newRun(s), "req");
        assertEquals(RunStatus.SUCCEEDED, run.status);
        assertEquals(2, calls.get("flaky").get());
        assertTrue(feedbackSeen.get(1).stream().anyMatch(f -> f.contains("downstream timeout")), "retry receives the error as feedback");
        Map<String, Object> m = Metrics.compute(run, audit(run));
        assertEquals(1L, m.get("retries"));
        assertEquals(1, ((Number) m.get("incidentsRecovered")).intValue());
        assertTrue(m.get("mttrMs") != null);
    }

    @Test
    void retriesAreBounded() throws Exception {
        agent("broken", ctx -> {
            throw new IllegalStateException("always fails");
        });
        StageDef s = st("broken");
        s.maxAttempts = 2;
        RunState run = engine().start(newRun(s), "req");
        assertEquals(RunStatus.FAILED, run.status);
        assertEquals(2, calls.get("broken").get());
    }

    @Test
    void downstreamFailureRoutesBackRollsBackAndReworks() throws Exception {
        agent("impl", ctx -> {
            AgentResult r = out("impl", "attempt");
            String v = ctx.feedback().isEmpty() ? "bad" : "good";
            r.changes.add(ctx.workspace().exists("src/main/A.txt")
                    ? replace("src/main/A.txt", v) : FileChange.create("src/main/A.txt", v));
            return r;
        });
        agent("check", ctx -> {
            String content = Files.readString(ctx.workspace().root().resolve("src/main/A.txt"));
            if (!content.equals("good")) {
                throw new IllegalStateException("verification failed: content=" + content);
            }
            return out("check", "verified");
        });
        StageDef impl = st("impl");
        impl.mutatesWorkspace = true;
        impl.writeScope = new ArrayList<>(List.of("src/main/**"));
        impl.produces = new ArrayList<>(List.of("impl-out", "impl-changes"));
        StageDef check = st("check", "impl");
        check.maxAttempts = 1;
        check.onFailure = "impl";
        RunState run = engine().start(newRun(impl, check), "req");

        assertEquals(RunStatus.SUCCEEDED, run.status);
        assertEquals("good", Files.readString(ws(run).resolve("src/main/A.txt")));
        assertEquals(2, calls.get("impl").get());
        assertEquals(1L, run.counter("reworks"));
        assertTrue(run.counter("rollbacks") >= 1, "the bad change set was rolled back before rework");
        assertTrue(events(run, "replan.rework") == 1);
    }

    @Test
    void signoffPausesPersistsAndResumesInAFreshEngine() throws Exception {
        agent("design", ctx -> out("design", "the design"));
        agent("build", ctx -> out("build", "built"));
        StageDef design = st("design");
        design.approval = "always";
        design.approvalRole = "tech-lead";
        design.risk = com.example.sdlc.model.Risk.MEDIUM;
        RunState run = engine().start(newRun(design, st("build", "design")), "req");
        assertEquals(RunStatus.PAUSED, run.status);
        assertEquals(0, calls.get("build").get(), "nothing downstream runs before sign-off");

        RunState loaded = store.load(run.runId); // simulate a different process
        ApprovalRequest pending = ApprovalService.pending(loaded).get(0);
        new ApprovalService(ApproverRegistry.load(null), Clock.systemUTC()).decide(loaded, pending.id, "alice", true, "ok");
        store.save(loaded);

        RunState resumed = engine().resume(run.runId);
        assertEquals(RunStatus.SUCCEEDED, resumed.status);
        assertEquals(1, calls.get("design").get(), "approved work is not redone");
        assertEquals(1, events(resumed, "approval.granted"));
    }

    @Test
    void rejectionSendsWorkBackWithTheReviewerComment() throws Exception {
        List<String> seen = new ArrayList<>();
        agent("design", ctx -> {
            seen.add(String.join("|", ctx.feedback()));
            return out("design", "design v" + ctx.feedback().size());
        });
        StageDef design = st("design");
        design.approval = "always";
        design.approvalRole = "tech-lead";
        design.onReject = "design";
        RunState run = engine().start(newRun(design), "req");
        ApprovalService approvals = new ApprovalService(ApproverRegistry.load(null), Clock.systemUTC());
        approvals.decide(run, ApprovalService.pending(run).get(0).id, "alice", false, "add a rollback section");
        store.save(run);
        run = engine().resume(run.runId);
        assertEquals(RunStatus.PAUSED, run.status, "reworked design needs a fresh sign-off");
        assertTrue(seen.get(1).contains("add a rollback section"));
        approvals.decide(run, ApprovalService.pending(run).get(0).id, "alice", true, null);
        store.save(run);
        assertEquals(RunStatus.SUCCEEDED, engine().resume(run.runId).status);
    }

    @Test
    void changedRequirementInvalidatesByLineageWithHashShortCircuit() throws Exception {
        agent("a", ctx -> out("a", "spec from: " + ctx.input("requirement")));
        agent("b", ctx -> out("b", ctx.input("a-out").contains("feature") ? "needs feature" : "no feature"));
        agent("c", ctx -> out("c", "built " + ctx.input("b-out")));
        agent("gate", ctx -> out("gate", "ready"));
        StageDef a = st("a");
        a.consumes = new ArrayList<>(List.of("requirement"));
        StageDef gate = st("gate", "c");
        gate.approval = "always";
        gate.approvalRole = "release-manager";
        RunState run = engine().start(newRun(a, st("b", "a"), st("c", "b"), gate), "build the feature");
        assertEquals(RunStatus.PAUSED, run.status);

        // Amend the requirement in a way that changes a's output but not b's.
        new ArtifactStore(store, Clock.systemUTC()).write(run, "requirement", "build the feature, faster", "human", 0, "human", Map.of());
        store.save(run);
        run = engine().resume(run.runId);

        assertEquals(2, calls.get("a").get(), "a consumed the changed requirement → re-ran");
        assertEquals(2, calls.get("b").get(), "b consumed a's new output → re-ran");
        assertEquals(1, calls.get("c").get(), "b produced identical output (same hash) → c NOT invalidated");
        assertEquals(RunStatus.PAUSED, run.status);
        assertEquals(NodeState.AWAITING_APPROVAL, run.node("gate").state, "gate still awaits its original sign-off");
        assertEquals(2L, run.counter("invalidations"));
    }

    @Test
    void plannerCanAddAStageToTheLiveGraph() throws Exception {
        agent("plan", ctx -> {
            AgentResult r = out("plan", "plan");
            StageDef extra = st("extra", "plan");
            extra.addedBy = "planner";
            extra.reason = "risky change detected";
            r.proposedStages.add(extra);
            r.addDependencies.put("ship", List.of("extra"));
            return r;
        });
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        agent("extra", ctx -> {
            order.add("extra");
            return out("extra", "checked");
        });
        agent("ship", ctx -> {
            order.add("ship");
            return out("ship", "shipped");
        });
        RunState run = engine().start(newRun(st("plan"), st("ship", "plan")), "req");
        assertEquals(RunStatus.SUCCEEDED, run.status);
        assertEquals(List.of("extra", "ship"), order, "new stage runs, and ship waits for it");
        assertTrue(run.stage("ship").dependsOn.contains("extra"));
        assertEquals(1, events(run, "replan.node-added"));
    }

    @Test
    void invalidPlannerProposalIsRejectedAndGraphUnchanged() throws Exception {
        agent("plan", ctx -> {
            AgentResult r = out("plan", "plan");
            r.addDependencies.put("plan", List.of("ship")); // would create a cycle
            return r;
        });
        agent("ship", ctx -> out("ship", "shipped"));
        RunState run = engine().start(newRun(st("plan"), st("ship", "plan")), "req");
        assertEquals(RunStatus.SUCCEEDED, run.status);
        assertEquals(List.of(), run.stage("plan").dependsOn);
        assertEquals(1, events(run, "replan.rejected"));
    }

    @Test
    void killSwitchSafeStopsAndRunResumes() throws Exception {
        agent("first", ctx -> {
            Files.writeString(store.runDir(ctx.runId()).resolve("STOP"), "operator test");
            return out("first", "done");
        });
        agent("second", ctx -> out("second", "done"));
        RunState run = engine().start(newRun(st("first"), st("second", "first")), "req");
        assertEquals(RunStatus.STOPPED, run.status);
        assertTrue(run.stopReason.contains("operator test"));
        assertEquals(0, calls.get("second").get());

        Files.delete(store.runDir(run.runId).resolve("STOP"));
        run = engine().resume(run.runId);
        assertEquals(RunStatus.SUCCEEDED, run.status);
        assertEquals(1, calls.get("second").get());
    }

    @Test
    void criticalPolicyViolationSafeStopsWithoutTouchingTheWorkspace() throws Exception {
        agent("leaky", ctx -> {
            AgentResult r = out("leaky", "x");
            r.changes.add(FileChange.create("src/main/Cfg.java", "String key = \"sk-ant-abcdefghijklmnopqrstuvwxyz123\";"));
            return r;
        });
        StageDef s = st("leaky");
        s.mutatesWorkspace = true;
        s.writeScope = new ArrayList<>(List.of("src/main/**"));
        s.maxAttempts = 3;
        RunState run = engine().start(newRun(s), "req");
        assertEquals(RunStatus.STOPPED, run.status);
        assertTrue(run.stopReason.contains("SEC-001"));
        assertEquals(1, calls.get("leaky").get(), "critical findings are never retried");
        assertFalse(Files.exists(ws(run).resolve("src/main/Cfg.java")));
    }

    @Test
    void terminalFailureCompensatesAllAppliedChanges() throws Exception {
        agent("writer", ctx -> {
            AgentResult r = out("writer", "w");
            r.changes.add(FileChange.create("src/main/Feature.java", "class Feature {}"));
            return r;
        });
        agent("doomed", ctx -> {
            throw new IllegalStateException("cannot proceed");
        });
        StageDef writer = st("writer");
        writer.mutatesWorkspace = true;
        writer.writeScope = new ArrayList<>(List.of("src/main/**"));
        writer.produces = new ArrayList<>(List.of("writer-out", "writer-changes"));
        StageDef doomed = st("doomed", "writer");
        doomed.maxAttempts = 2;
        RunState run = engine().start(newRun(writer, doomed), "req");
        assertEquals(RunStatus.FAILED, run.status);
        assertFalse(Files.exists(ws(run).resolve("src/main/Feature.java")), "saga compensation restored the workspace");
        assertTrue(events(run, "run.failed") == 1);
    }

    @Test
    void policyGatedChangeIsAppliedOnlyAfterApproval() throws Exception {
        agent("migrate", ctx -> {
            AgentResult r = out("migrate", "m");
            r.changes.add(FileChange.create("src/main/resources/db/migration/V2__add.sql",
                    "ALTER TABLE t ADD COLUMN c INT; -- rollback: previous version ignores it"));
            return r;
        });
        StageDef s = st("migrate");
        s.mutatesWorkspace = true;
        s.writeScope = new ArrayList<>(List.of("src/main/**"));
        s.produces = new ArrayList<>(List.of("migrate-out", "migrate-changes"));
        RunState run = engine().start(newRun(s), "req");
        Path file = ws(run).resolve("src/main/resources/db/migration/V2__add.sql");
        assertEquals(RunStatus.PAUSED, run.status);
        assertFalse(Files.exists(file), "nothing is written before approval");
        ApprovalRequest a = ApprovalService.pending(run).get(0);
        assertEquals("tech-lead", a.requiredRole);

        new ApprovalService(ApproverRegistry.load(null), Clock.systemUTC()).decide(run, a.id, "alice", true, "ok");
        store.save(run);
        run = engine().resume(run.runId);
        assertEquals(RunStatus.SUCCEEDED, run.status);
        assertTrue(Files.exists(file));
        assertEquals(1, calls.get("migrate").get(), "the approved change set is applied as-is, not regenerated");
        assertTrue(Json.parseObject(new ArtifactStore(store, Clock.systemUTC()).read(run, "migrate-changes").orElseThrow())
                .get("changes").toString().contains("V2__add.sql"));
    }

    @Test
    void exhaustedModelAttemptsFallBackToTheDeterministicAgent() throws Exception {
        List<String> modes = Collections.synchronizedList(new ArrayList<>());
        // Named like a real LLM-capable agent, so the engine offers a fallback for it.
        agent("implementation", ctx -> {
            modes.add(ctx.fallbackMode() ? "fallback" : "llm");
            if (!ctx.fallbackMode()) {
                throw new IllegalStateException("model output failed validation");
            }
            AgentResult r = out("impl", "from verified playbook");
            r.mode = "fallback";
            return r;
        });
        StageDef impl = st("impl");
        impl.agent = "implementation";
        impl.maxAttempts = 2;
        RunState run = engine(new UnreliableLlm()).start(newRun(impl), "req");

        assertEquals(RunStatus.SUCCEEDED, run.status);
        assertEquals(List.of("llm", "llm", "fallback"), modes, "two bounded model attempts, then one deterministic attempt");
        assertTrue(run.node("impl").usedFallback);
        assertEquals(1L, run.counter("fallbacks"));
        assertEquals("fallback", run.latest("impl-out").orElseThrow().mode, "lineage records that the fallback produced it");
        assertEquals(1, events(run, "stage.fallback"));
    }

    @Test
    void noFallbackLoopWhenTheModelIsUnavailable() throws Exception {
        agent("implementation", ctx -> {
            throw new IllegalStateException("deterministic path also fails");
        });
        StageDef impl = st("impl");
        impl.agent = "implementation";
        impl.maxAttempts = 2;
        RunState run = engine().start(newRun(impl), "req");
        assertEquals(RunStatus.FAILED, run.status);
        assertEquals(2, calls.get("implementation").get(), "offline: fallback would be the same path, so it is not retried again");
        assertEquals(0L, run.counter("fallbacks"));
    }

    private static FileChange replace(String path, String content) {
        FileChange c = new FileChange();
        c.path = path;
        c.op = FileChange.Op.REPLACE;
        c.content = content;
        return c;
    }
}
