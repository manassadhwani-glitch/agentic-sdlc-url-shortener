package com.example.sdlc.engine;

import com.example.sdlc.agents.Agent;
import com.example.sdlc.agents.AgentContext;
import com.example.sdlc.agents.Catalog;
import com.example.sdlc.audit.AuditException;
import com.example.sdlc.audit.AuditLog;
import com.example.sdlc.gates.Gates;
import com.example.sdlc.gates.Gates.GateResult;
import com.example.sdlc.json.Json;
import com.example.sdlc.llm.LlmClient;
import com.example.sdlc.model.ApprovalRequest;
import com.example.sdlc.model.ArtifactMeta;
import com.example.sdlc.model.NodeRun;
import com.example.sdlc.model.NodeState;
import com.example.sdlc.model.Question;
import com.example.sdlc.model.Risk;
import com.example.sdlc.model.RunState;
import com.example.sdlc.model.RunStatus;
import com.example.sdlc.model.StageDef;
import com.example.sdlc.policy.PolicyEngine;
import com.example.sdlc.policy.PolicyFinding;
import com.example.sdlc.store.ArtifactStore;
import com.example.sdlc.store.RunStore;
import com.example.sdlc.tools.TestRunner;
import com.example.sdlc.workflow.WorkflowValidator;
import com.example.sdlc.workspace.GuardedWorkspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The orchestration engine: a stateful, governed executor for a dependency graph of SDLC stages.
 *
 * <h2>Concurrency model</h2>
 * One <b>coordinator</b> thread (the caller of {@link #start}/{@link #resume}) owns all run state. It promotes
 * ready stages, launches them on a bounded worker pool, and applies each {@link Outcome} as it completes. Workers
 * only compute; they get immutable input snapshots. That makes transitions race-free without locks on run state,
 * and every transition is persisted before the next one happens.
 *
 * <h2>Control flow</h2>
 * <ul>
 *   <li>Parallel paths with synchronization: a stage starts when all of its dependencies are done (a join).</li>
 *   <li>Non-linear routing: a failed stage can route back upstream ({@code onFailure}); a human rejection can send
 *       work back to a design stage ({@code onReject}). Both are bounded by {@code maxReworks}.</li>
 *   <li>Re-planning: before each step the coordinator reconciles lineage. Any finished stage whose consumed input
 *       now has a newer version is invalidated (its workspace changes are rolled back) and re-run. Because artifacts
 *       are content-addressed, identical re-generated output does not ripple further downstream. Planner agents
 *       can also add new stages to the live graph, which is re-validated first.</li>
 *   <li>Reliability: bounded retries with exponential backoff and feedback; fallback to the deterministic agent;
 *       journal rollback on failed exit gates; saga compensation (reverse rollback) on terminal failure; safe-stop
 *       on kill switch, budget exhaustion, a critical policy violation, or audit failure.</li>
 *   <li>Human checkpoints: change sets that policy marks REQUIRE_APPROVAL, and stages that need sign-off,
 *       pause the run (state saved to disk) until {@code resume}.</li>
 * </ul>
 */
public final class Engine {

    private static final Set<String> LLM_CAPABLE = Set.of("requirements", "design", "implementation", "security-review");

    private final RunStore store;
    private final ArtifactStore artifacts;
    private final Map<String, Agent> agents;
    private final Gates gates;
    private final PolicyEngine policy;
    private final LlmClient llm;
    private final Catalog catalog;
    private final TestRunner testRunner;
    private final Clock clock;
    private final int parallelism;
    private final Consumer<String> console;
    private final StageWorker worker;
    private final WorkflowValidator validator;

    public Engine(RunStore store, Map<String, Agent> agents, Gates gates, PolicyEngine policy, LlmClient llm,
                  Catalog catalog, TestRunner testRunner, Clock clock, int parallelism, Consumer<String> console) {
        this.store = store;
        this.artifacts = new ArtifactStore(store, clock);
        this.agents = agents;
        this.gates = gates;
        this.policy = policy;
        this.llm = llm;
        this.catalog = catalog;
        this.testRunner = testRunner;
        this.clock = clock;
        this.parallelism = Math.max(1, parallelism);
        this.console = console;
        this.worker = new StageWorker(gates, policy);
        this.validator = new WorkflowValidator(agents.keySet(), gates.names());
    }

    public ArtifactStore artifacts() {
        return artifacts;
    }

    public WorkflowValidator validator() {
        return validator;
    }

    // ================================================================== lifecycle

    /** Validates the workflow, records the requirement as artifact v1, and executes until done or paused. */
    public RunState start(RunState run, String requirement) {
        List<String> errors = validator.validate(run.stages);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("workflow is invalid: " + errors);
        }
        for (StageDef s : run.stages) {
            run.nodes.put(s.id, new NodeRun(s.id));
        }
        run.createdAt = clock.instant().toString();
        run.status = RunStatus.RUNNING;
        AuditLog audit = audit(run);
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("workflow", run.workflow);
        started.put("waves", WorkflowValidator.waves(run.stages));
        started.put("llm", llm.describe());
        started.put("config", run.config);
        started.put("auditKey", audit.usingDevKey() ? "DEV KEY (set ORCH_AUDIT_KEY)" : "configured");
        audit.append("run.started", run.initiator, null, started);
        ArtifactMeta req = artifacts.write(run, "requirement", requirement, "human", 0, "human", Map.of()).meta();
        audit.append("artifact.written", run.initiator, null, Map.of("artifact", req.ref(), "sha256", req.sha256));
        store.save(run);
        say("▶ run " + run.runId + " started (" + run.stages.size() + " stages, llm: " + llm.describe() + ")");
        return execute(run);
    }

    /** Continues a paused or stopped run after applying human decisions recorded since it paused. */
    public RunState resume(String runId) {
        RunState run = store.load(runId);
        if (run.status.isTerminal()) {
            say("run " + runId + " is already " + run.status);
            return run;
        }
        AuditLog audit = audit(run);
        GuardedWorkspace ws = workspace(run);
        applyHumanDecisions(run, audit, ws);
        for (NodeRun n : run.nodes.values()) {
            if (n.state == NodeState.CANCELLED) {
                n.state = NodeState.PENDING;
            }
        }
        run.stopReason = null;
        run.config.remove("fatal");
        try {
            if (Files.deleteIfExists(store.runDir(runId).resolve("STOP"))) {
                audit.append("killswitch.cleared", "operator", null, Map.of());
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot clear kill switch", e);
        }
        audit.append("run.resumed", "system", null, Map.of("planVersion", run.planVersion));
        store.save(run);
        say("▶ resuming " + runId);
        return execute(run);
    }

    // ================================================================== coordinator loop

    private RunState execute(RunState run) {
        AuditLog audit = audit(run);
        GuardedWorkspace ws = workspace(run);
        AtomicInteger threadNo = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "agent-worker-" + threadNo.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        BlockingQueue<Outcome> done = new LinkedBlockingQueue<>();
        Set<String> inFlight = new HashSet<>();
        long sessionStart = clock.millis();
        run.status = RunStatus.RUNNING;
        store.save(run);
        try {
            while (true) {
                String stop = stopReason(run, sessionStart);
                if (stop != null) {
                    safeStop(run, audit, ws, stop, inFlight, done);
                    return finish(run, audit);
                }
                reconcile(run, audit, ws);
                promote(run);
                boolean failing = run.nodes.values().stream().anyMatch(n -> n.state == NodeState.FAILED);
                if (!failing) {
                    for (StageDef s : run.stages) {
                        NodeRun n = run.nodes.get(s.id);
                        if (n.state == NodeState.READY && inFlight.size() < parallelism) {
                            launch(run, s, n, audit, ws, pool, done);
                            inFlight.add(s.id);
                        }
                    }
                }
                if (inFlight.isEmpty()) {
                    if (failing) {
                        compensate(run, audit, ws);
                        return finish(run, audit);
                    }
                    if (run.nodes.values().stream().allMatch(n -> n.state.isDone())) {
                        run.status = RunStatus.SUCCEEDED;
                        run.finishedAt = clock.instant().toString();
                        audit.append("run.succeeded", "system", null, Map.of("planVersion", run.planVersion));
                        say("✔ run SUCCEEDED");
                        return finish(run, audit);
                    }
                    Optional<Long> nextRetry = run.nodes.values().stream().filter(n -> n.state == NodeState.RETRY_WAIT)
                            .map(n -> n.nextAttemptAtMs).min(Long::compare);
                    if (nextRetry.isPresent()) {
                        Thread.sleep(Math.max(10, Math.min(1000, nextRetry.get() - clock.millis())));
                        continue;
                    }
                    if (run.nodes.values().stream().anyMatch(n -> n.state.isWaitingForHuman())) {
                        run.status = RunStatus.PAUSED;
                        audit.append("run.paused", "system", null, Map.of("waitingFor", waitingSummary(run)));
                        say("⏸ run PAUSED, waiting for a human: " + waitingSummary(run));
                        return finish(run, audit);
                    }
                    run.status = RunStatus.FAILED;
                    run.stopReason = "deadlock: no runnable stages";
                    compensate(run, audit, ws);
                    return finish(run, audit);
                }
                Outcome o = done.poll(200, TimeUnit.MILLISECONDS);
                if (o != null) {
                    inFlight.remove(o.stageId);
                    apply(run, o, audit, ws);
                    store.save(run);
                }
            }
        } catch (AuditException e) {
            // Fail-closed: without an audit trail we refuse to continue.
            run.status = RunStatus.STOPPED;
            run.stopReason = "audit trail unavailable (fail-closed): " + e.getMessage();
            store.save(run);
            say("■ STOPPED: " + run.stopReason);
            return run;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.status = RunStatus.STOPPED;
            run.stopReason = "interrupted";
            store.save(run);
            return run;
        } finally {
            pool.shutdownNow();
        }
    }

    private RunState finish(RunState run, AuditLog audit) {
        if (run.status.isTerminal() && run.finishedAt == null) {
            run.finishedAt = clock.instant().toString();
        }
        store.save(run);
        return run;
    }

    // ================================================================== launch

    private void launch(RunState run, StageDef s, NodeRun n, AuditLog audit, GuardedWorkspace ws,
                        ExecutorService pool, BlockingQueue<Outcome> done) {
        String action = n.pendingAction;
        n.pendingAction = null;
        Map<String, Object> approvedProposal = null;
        if ("apply-approved".equals(action)) {
            approvedProposal = Json.parseObject(artifacts.read(run, s.id + "-proposed").orElseThrow());
        }

        Map<String, String> inputs = new LinkedHashMap<>();
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (String c : s.consumes) {
            boolean optional = c.endsWith("?");
            String name = optional ? c.substring(0, c.length() - 1) : c;
            Optional<ArtifactMeta> meta = run.latest(name);
            if (meta.isPresent()) {
                inputs.put(name, artifacts.read(run, meta.get()));
                versions.put(name, meta.get().version);
            }
        }
        List<Question> answers = run.questions.stream().filter(q -> q.stageId.equals(s.id) && !q.isOpen()).toList();
        Map<String, Object> config = new LinkedHashMap<>(run.config);
        List<Object> approvals = new ArrayList<>();
        run.approvals.forEach(a -> approvals.add(a.toJson()));
        config.put("approvalsSummary", approvals);

        n.state = NodeState.RUNNING;
        n.attempts++;
        n.totalAttempts++;
        run.increment("attempts");
        if (n.firstStartedAt == null) {
            n.firstStartedAt = clock.instant().toString();
        }
        boolean fallback = n.usedFallback;
        Set<String> faults = new HashSet<>(Json.strings(run.config.get("faults")));
        Path reference = run.config.get("referenceDir") == null ? null : Path.of(String.valueOf(run.config.get("referenceDir")));
        AgentContext ctx = new AgentContext(run.runId, artifacts.read(run, "requirement").orElse(""), s, n.attempts, fallback,
                Map.copyOf(inputs), Map.copyOf(versions), List.copyOf(n.feedback), answers, ws, llm, catalog, faults,
                reference, testRunner, config);
        String journalId = s.id + "-a" + n.totalAttempts;
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("attempt", n.attempts);
        started.put("inputs", versions);
        started.put("mode", approvedProposal != null ? "apply-approved" : fallback ? "fallback" : llm.isAvailable() ? "llm" : "deterministic");
        audit.append("stage.started", "agent:" + s.agent, s.id, started);
        store.save(run);
        say("  → " + s.id + " started (attempt " + n.attempts + (approvedProposal != null ? ", applying approved change set" : "") + ")");

        Agent agent = agents.get(s.agent);
        Map<String, Object> proposal = approvedProposal;
        long now = clock.millis();
        pool.submit(() -> done.add(worker.execute(agent, ctx, journalId, proposal, now)));
    }

    // ================================================================== apply outcome (coordinator thread only)

    private void apply(RunState run, Outcome o, AuditLog audit, GuardedWorkspace ws) {
        StageDef s = run.stage(o.stageId);
        NodeRun n = run.node(o.stageId);
        long durationMs = Math.max(0, clock.millis() - o.startedMs);
        if (o.agent != null) {
            run.counters.merge("llmCalls", (long) o.agent.llmCalls, Long::sum);
            for (Map<String, Object> d : o.agent.decisions) {
                Map<String, Object> rec = new LinkedHashMap<>(d);
                rec.put("inputs", o.inputVersions);
                audit.append("decision", "agent:" + s.agent, s.id, rec);
            }
        }
        for (GateResult g : o.gates) {
            audit.append("gate", "system", s.id, g.toMap());
        }
        for (PolicyFinding f : o.findings) {
            audit.append("policy.finding", "policy", s.id, f.toMap());
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("attempt", n.attempts);
        entry.put("startedAtMs", o.startedMs);
        entry.put("durationMs", durationMs);
        entry.put("outcome", o.kind);
        entry.put("mode", o.mode);
        entry.put("error", o.error);
        n.attemptLog.add(entry);
        n.mode = o.mode;
        audit.append("stage.attempt", "agent:" + s.agent, s.id, entry);

        switch (o.kind) {
            case SUCCESS -> onSuccess(run, s, n, o, audit);
            case NEEDS_APPROVAL -> onNeedsApproval(run, s, n, o, audit);
            case CLARIFY -> onClarify(run, s, n, o, audit);
            case FAILED -> onFailed(run, s, n, o, audit, ws);
            case FATAL -> {
                n.state = NodeState.FAILED;
                n.lastError = o.error;
                run.config.put("fatal", o.error);
                audit.append("stage.fatal", "policy", s.id, Map.of("error", o.error));
                say("  ✖ " + s.id + " FATAL: " + o.error);
            }
        }
    }

    private void onSuccess(RunState run, StageDef s, NodeRun n, Outcome o, AuditLog audit) {
        if (o.journal != null) {
            n.appliedJournals.add(o.journal);
            run.journalOrder.add(o.journal);
        }
        for (Map.Entry<String, String> out : o.outputs.entrySet()) {
            ArtifactStore.WriteResult w = artifacts.write(run, out.getKey(), out.getValue(), s.id, n.totalAttempts, o.mode, o.inputVersions);
            audit.append("artifact.written", "agent:" + s.agent, s.id,
                    Map.of("artifact", w.meta().ref(), "sha256", w.meta().sha256, "changed", w.changed()));
        }
        n.consumed.clear();
        n.consumed.putAll(o.inputVersions);
        applyProposals(run, s, o, audit);
        if (n.firstFailedAt != null && n.attemptLog.stream().anyMatch(a -> !"SUCCESS".equals(String.valueOf(a.get("outcome"))))) {
            long mttr = clock.millis() - Instant.parse(n.firstFailedAt).toEpochMilli();
            audit.append("stage.recovered", "system", s.id, Map.of("recoveryMs", mttr));
        }
        if (s.requiresSignoff()) {
            ApprovalRequest a = newApproval(run, s, ApprovalRequest.Kind.SIGNOFF, s.approvalRole, s.risk,
                    List.of("sign-off required for stage " + s.id), String.join(", ", o.outputs.keySet()));
            n.pendingApprovalId = a.id;
            n.state = NodeState.AWAITING_APPROVAL;
            audit.append("approval.requested", "system", s.id, a.toJson());
            say("  ⏸ " + s.id + " awaiting sign-off " + a.id + " (role " + a.requiredRole + ")");
        } else {
            n.state = NodeState.SUCCEEDED;
            n.finishedAt = clock.instant().toString();
            audit.append("stage.succeeded", "system", s.id, Map.of("attempts", n.attempts));
            say("  ✔ " + s.id + " succeeded" + (n.attempts > 1 ? " (attempt " + n.attempts + ")" : ""));
        }
    }

    private void onNeedsApproval(RunState run, StageDef s, NodeRun n, Outcome o, AuditLog audit) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("outputs", o.outputs);
        proposal.put("changes", o.changes);
        List<Object> findings = new ArrayList<>();
        o.findings.forEach(f -> findings.add(f.toMap()));
        proposal.put("findings", findings);
        ArtifactMeta meta = artifacts.write(run, s.id + "-proposed", Json.pretty(proposal), s.id, n.totalAttempts, o.mode, o.inputVersions).meta();
        n.consumed.clear();
        n.consumed.putAll(o.inputVersions);
        List<String> ids = new ArrayList<>();
        Risk risk = s.risk == Risk.HIGH ? Risk.HIGH : Risk.MEDIUM; // policy-gated changes are never LOW risk
        for (String role : PolicyEngine.requiredRoles(o.findings)) {
            List<String> reasons = o.findings.stream()
                    .filter(f -> role.equals(f.requiredRole())).map(PolicyFinding::summary).toList();
            ApprovalRequest a = newApproval(run, s, ApprovalRequest.Kind.CHANGE_SET, role, risk, reasons, meta.ref());
            ids.add(a.id);
            audit.append("approval.requested", "policy", s.id, a.toJson());
            say("  ⏸ " + s.id + " change set needs approval " + a.id + " (role " + role + "): " + reasons);
        }
        n.pendingApprovalId = String.join(",", ids);
        n.state = NodeState.AWAITING_APPROVAL;
    }

    private void onClarify(RunState run, StageDef s, NodeRun n, Outcome o, AuditLog audit) {
        for (Question q : o.agent.questions) {
            boolean exists = run.questions.stream().anyMatch(x -> x.id.equals(q.id) && x.stageId.equals(q.stageId) && x.isOpen());
            if (!exists) {
                run.questions.add(q);
                audit.append("question.raised", "agent:" + s.agent, s.id, q.toJson());
                say("  ? " + s.id + " asks " + q.id + ": " + q.text);
            }
        }
        n.state = NodeState.AWAITING_CLARIFICATION;
    }

    private void onFailed(RunState run, StageDef s, NodeRun n, Outcome o, AuditLog audit, GuardedWorkspace ws) {
        n.lastError = o.error;
        if (n.firstFailedAt == null) {
            n.firstFailedAt = clock.instant().toString();
        }
        addFeedback(n, o.feedback);
        run.increment("failedAttempts");
        if (o.rolledBack) {
            run.increment("rollbacks");
            audit.append("rollback", "system", s.id, Map.of("journal", o.journal == null ? "" : o.journal, "reason", "exit gate failed"));
        }
        say("  ✖ " + s.id + " attempt " + n.attempts + " failed: " + abbreviate(o.error) + (o.rolledBack ? " (changes rolled back)" : ""));
        if (o.retryable && n.attempts < s.maxAttempts) {
            long delay = s.backoffMs * (1L << Math.min(6, n.attempts - 1));
            n.state = NodeState.RETRY_WAIT;
            n.nextAttemptAtMs = clock.millis() + delay;
            run.increment("retries");
            audit.append("stage.retry-scheduled", "system", s.id, Map.of("nextAttempt", n.attempts + 1, "backoffMs", delay));
            return;
        }
        if (o.retryable && LLM_CAPABLE.contains(s.agent) && llm.isAvailable() && !n.usedFallback) {
            n.usedFallback = true;
            n.state = NodeState.RETRY_WAIT;
            n.nextAttemptAtMs = clock.millis();
            run.increment("fallbacks");
            audit.append("stage.fallback", "system", s.id, Map.of("reason", "retries exhausted; switching to deterministic agent"));
            say("  ↺ " + s.id + " falling back to the deterministic agent");
            return;
        }
        terminalFailure(run, s, n, o, audit, ws);
    }

    private void terminalFailure(RunState run, StageDef s, NodeRun n, Outcome o, AuditLog audit, GuardedWorkspace ws) {
        if (s.onFailure != null) {
            StageDef ts = run.stage(s.onFailure);
            NodeRun tn = run.node(s.onFailure);
            if (tn.reworks < ts.maxReworks) {
                tn.reworks++;
                run.increment("reworks");
                List<String> fb = new ArrayList<>();
                fb.add("Downstream stage '" + s.id + "' failed: " + abbreviate(String.join(" | ", o.feedback)));
                resetNode(run, tn, audit, ws, "rework after " + s.id + " failed", true);
                addFeedback(tn, fb);
                resetNode(run, n, audit, ws, "waiting for rework of " + ts.id, false);
                run.planVersion++;
                audit.append("replan.rework", "system", s.id, Map.of("routeTo", ts.id, "rework", tn.reworks, "reason", fb.get(0)));
                say("  ↻ routing back to " + ts.id + " (rework " + tn.reworks + "/" + ts.maxReworks + ")");
                return;
            }
        }
        n.state = NodeState.FAILED;
        n.finishedAt = clock.instant().toString();
        audit.append("stage.failed", "system", s.id, Map.of("error", String.valueOf(o.error)));
        say("  ✖ " + s.id + " FAILED (no retries left)");
    }

    // ================================================================== re-planning

    /** Lineage reconciliation: invalidate finished stages whose inputs changed since they ran. */
    private void reconcile(RunState run, AuditLog audit, GuardedWorkspace ws) {
        for (StageDef s : run.stages) {
            NodeRun n = run.nodes.get(s.id);
            boolean finished = n.state == NodeState.SUCCEEDED
                    || (n.state == NodeState.AWAITING_APPROVAL && n.pendingApprovalId != null);
            if (!finished || n.consumed.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Integer> c : n.consumed.entrySet()) {
                Optional<ArtifactMeta> latest = run.latest(c.getKey());
                if (latest.isPresent() && latest.get().version > c.getValue()) {
                    run.increment("invalidations");
                    run.planVersion++;
                    audit.append("replan.invalidated", "system", s.id, Map.of("artifact", c.getKey(),
                            "consumedVersion", c.getValue(), "latestVersion", latest.get().version));
                    say("  ↻ " + s.id + " invalidated: " + c.getKey() + " v" + c.getValue() + " → v" + latest.get().version);
                    n.invalidations++;
                    resetNode(run, n, audit, ws, "input " + c.getKey() + " changed", true);
                    n.feedback.clear();
                    break;
                }
            }
        }
    }

    private void applyProposals(RunState run, StageDef s, Outcome o, AuditLog audit) {
        if (o.agent == null || (o.agent.proposedStages.isEmpty() && o.agent.addDependencies.isEmpty())) {
            return;
        }
        List<StageDef> candidate = new ArrayList<>(run.stages);
        List<StageDef> added = new ArrayList<>();
        for (StageDef p : o.agent.proposedStages) {
            if (candidate.stream().noneMatch(x -> x.id.equals(p.id))) {
                candidate.add(p);
                added.add(p);
            }
        }
        Map<String, List<String>> originalDeps = new HashMap<>();
        for (Map.Entry<String, List<String>> d : o.agent.addDependencies.entrySet()) {
            candidate.stream().filter(x -> x.id.equals(d.getKey())).findFirst().ifPresent(x -> {
                originalDeps.put(x.id, new ArrayList<>(x.dependsOn));
                for (String dep : d.getValue()) {
                    if (!x.dependsOn.contains(dep)) {
                        x.dependsOn.add(dep);
                    }
                }
            });
        }
        List<String> errors = validator.validate(candidate);
        if (!errors.isEmpty()) {
            originalDeps.forEach((id, deps) -> run.stage(id).dependsOn = deps);
            audit.append("replan.rejected", "system", s.id, Map.of("errors", errors));
            say("  ! re-plan proposal from " + s.id + " rejected: " + errors);
            return;
        }
        run.stages = candidate;
        for (StageDef p : added) {
            run.nodes.put(p.id, new NodeRun(p.id));
            run.planVersion++;
            audit.append("replan.node-added", "agent:" + s.agent, p.id, Map.of("stage", p.toJson(), "reason", String.valueOf(p.reason)));
            say("  + planner added stage '" + p.id + "': " + p.reason);
        }
        originalDeps.keySet().forEach(id ->
                audit.append("replan.edge-added", "agent:" + s.agent, id, Map.of("dependsOn", run.stage(id).dependsOn)));
    }

    // ================================================================== human decisions (applied on resume)

    private void applyHumanDecisions(RunState run, AuditLog audit, GuardedWorkspace ws) {
        for (StageDef s : run.stages) {
            NodeRun n = run.nodes.get(s.id);
            if (n.state == NodeState.AWAITING_APPROVAL && n.pendingApprovalId != null) {
                List<ApprovalRequest> round = new ArrayList<>();
                for (String id : n.pendingApprovalId.split(",")) {
                    run.approval(id).ifPresent(round::add);
                }
                Optional<ApprovalRequest> rejected = round.stream().filter(a -> a.status == ApprovalRequest.Status.REJECTED).findFirst();
                if (rejected.isPresent()) {
                    ApprovalRequest r = rejected.get();
                    audit.append("approval.rejected", "human:" + r.decidedBy, s.id, r.toJson());
                    round.stream().filter(a -> a.status == ApprovalRequest.Status.PENDING).forEach(a -> a.status = ApprovalRequest.Status.SUPERSEDED);
                    String targetId = s.onReject != null ? s.onReject : s.id;
                    StageDef ts = run.stage(targetId);
                    NodeRun tn = run.node(targetId);
                    if (tn.reworks < ts.maxReworks) {
                        tn.reworks++;
                        run.increment("reworks");
                        resetNode(run, tn, audit, ws, "rejected by " + r.decidedBy, true);
                        addFeedback(tn, List.of("Rejected by " + r.decidedBy + " (" + r.decidedRole + "): " + r.comment));
                        if (tn != n) {
                            resetNode(run, n, audit, ws, "upstream rework", true);
                        }
                        run.planVersion++;
                        audit.append("replan.rework", "human:" + r.decidedBy, targetId, Map.of("rework", tn.reworks, "comment", r.comment));
                        say("  ↻ " + targetId + " will be reworked: " + r.comment);
                    } else {
                        n.state = NodeState.FAILED;
                        n.lastError = "rejected and rework budget exhausted";
                        audit.append("stage.failed", "system", s.id, Map.of("error", n.lastError));
                    }
                    n.pendingApprovalId = null;
                } else if (!round.isEmpty() && round.stream().allMatch(a -> a.status == ApprovalRequest.Status.APPROVED)) {
                    for (ApprovalRequest a : round) {
                        audit.append("approval.granted", "human:" + a.decidedBy, s.id, a.toJson());
                    }
                    if (round.get(0).kind == ApprovalRequest.Kind.CHANGE_SET) {
                        n.state = NodeState.READY;
                        n.pendingAction = "apply-approved";
                    } else {
                        n.state = NodeState.SUCCEEDED;
                        n.finishedAt = clock.instant().toString();
                        audit.append("stage.succeeded", "system", s.id, Map.of("signedOffBy", round.get(0).decidedBy));
                    }
                    n.pendingApprovalId = null;
                    say("  ✔ " + s.id + " approved by " + round.stream().map(a -> a.decidedBy).distinct().toList());
                }
            } else if (n.state == NodeState.AWAITING_CLARIFICATION) {
                List<Question> qs = run.questions.stream().filter(q -> q.stageId.equals(s.id)).toList();
                if (!qs.isEmpty() && qs.stream().noneMatch(Question::isOpen)) {
                    for (Question q : qs) {
                        audit.append("question.answered", "human:" + q.answeredBy, s.id, q.toJson());
                    }
                    n.state = NodeState.READY;
                    n.attempts = 0;
                    say("  ✔ " + s.id + " clarified; re-running with answers");
                }
            }
        }
    }

    // ================================================================== reliability controls

    /** Puts a node back to PENDING; rolls back any workspace changes it applied (newest first). */
    private void resetNode(RunState run, NodeRun n, AuditLog audit, GuardedWorkspace ws, String reason, boolean rollback) {
        if (rollback) {
            for (int i = n.appliedJournals.size() - 1; i >= 0; i--) {
                String j = n.appliedJournals.get(i);
                int files = ws.rollback(j);
                run.increment("rollbacks");
                audit.append("rollback", "system", n.stageId, Map.of("journal", j, "files", files, "reason", reason));
            }
            n.appliedJournals.clear();
        }
        if (n.pendingApprovalId != null) {
            for (String id : n.pendingApprovalId.split(",")) {
                run.approval(id).filter(a -> a.status == ApprovalRequest.Status.PENDING)
                        .ifPresent(a -> a.status = ApprovalRequest.Status.SUPERSEDED);
            }
            n.pendingApprovalId = null;
        }
        n.state = NodeState.PENDING;
        n.attempts = 0;
        n.usedFallback = false;
        n.pendingAction = null;
        n.finishedAt = null;
    }

    /** Saga compensation on terminal failure: undo every applied journal in reverse order. */
    private void compensate(RunState run, AuditLog audit, GuardedWorkspace ws) {
        List<String> undone = new ArrayList<>();
        for (int i = run.journalOrder.size() - 1; i >= 0; i--) {
            String j = run.journalOrder.get(i);
            if (ws.rollback(j) > 0) {
                undone.add(j);
                run.increment("rollbacks");
            }
        }
        run.nodes.values().forEach(n -> n.appliedJournals.clear());
        run.status = RunStatus.FAILED;
        if (run.stopReason == null) {
            run.stopReason = "stage failure: " + run.nodes.values().stream().filter(n -> n.state == NodeState.FAILED)
                    .map(n -> n.stageId + " (" + abbreviate(n.lastError) + ")").toList();
        }
        audit.append("run.failed", "system", null, Map.of("reason", run.stopReason, "compensated", undone));
        say("✖ run FAILED; workspace restored (" + undone.size() + " change sets undone): " + run.stopReason);
    }

    private String stopReason(RunState run, long sessionStart) {
        Path stopFile = store.runDir(run.runId).resolve("STOP");
        if (Files.exists(stopFile)) {
            try {
                return "operator kill switch: " + Files.readString(stopFile, StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                return "operator kill switch";
            }
        }
        if (run.config.get("fatal") != null) {
            return String.valueOf(run.config.get("fatal"));
        }
        if (run.counter("attempts") > run.cfgLong("maxTotalAttempts", 40)) {
            return "budget exhausted: more than " + run.cfgLong("maxTotalAttempts", 40) + " attempts";
        }
        if (clock.millis() - sessionStart > run.cfgLong("maxSessionMinutes", 60) * 60_000) {
            return "budget exhausted: session wall-clock limit";
        }
        if (run.counter("llmCalls") > run.cfgLong("maxLlmCalls", 60)) {
            return "budget exhausted: LLM call limit";
        }
        return null;
    }

    /** Safe-stop: wait for in-flight attempts, undo their unapplied effects, keep completed work, stay resumable. */
    private void safeStop(RunState run, AuditLog audit, GuardedWorkspace ws, String reason,
                          Set<String> inFlight, BlockingQueue<Outcome> done) throws InterruptedException {
        long deadline = clock.millis() + Duration.ofMinutes(15).toMillis();
        while (!inFlight.isEmpty() && clock.millis() < deadline) {
            Outcome o = done.poll(500, TimeUnit.MILLISECONDS);
            if (o != null) {
                inFlight.remove(o.stageId);
                if (o.journal != null && !o.rolledBack) {
                    ws.rollback(o.journal);
                    run.increment("rollbacks");
                    audit.append("rollback", "system", o.stageId, Map.of("journal", o.journal, "reason", "safe-stop"));
                }
                NodeRun n = run.node(o.stageId);
                n.state = NodeState.CANCELLED;
                n.attempts = Math.max(0, n.attempts - 1);
            }
        }
        for (NodeRun n : run.nodes.values()) {
            if (n.state == NodeState.RUNNING || n.state == NodeState.READY || n.state == NodeState.RETRY_WAIT
                    || n.state == NodeState.FAILED) {
                n.state = NodeState.CANCELLED;
            }
        }
        run.status = RunStatus.STOPPED;
        run.stopReason = reason;
        audit.append("run.stopped", "system", null, Map.of("reason", reason));
        say("■ run SAFE-STOPPED: " + reason + " (completed work kept; resume with `resume " + run.runId + "`)");
    }

    // ================================================================== helpers

    private void promote(RunState run) {
        long now = clock.millis();
        for (StageDef s : run.stages) {
            NodeRun n = run.nodes.get(s.id);
            if (n.state == NodeState.PENDING) {
                boolean depsDone = s.dependsOn.stream().allMatch(d -> run.node(d).state.isDone());
                if (depsDone) {
                    n.state = NodeState.READY;
                }
            } else if (n.state == NodeState.RETRY_WAIT && n.nextAttemptAtMs <= now) {
                n.state = NodeState.READY;
            }
        }
    }

    private ApprovalRequest newApproval(RunState run, StageDef s, ApprovalRequest.Kind kind, String role, Risk risk,
                                        List<String> reasons, String subject) {
        ApprovalRequest a = new ApprovalRequest();
        a.id = "A-" + run.increment("approvalSeq");
        a.stageId = s.id;
        a.kind = kind;
        a.risk = risk;
        a.requiredRole = role;
        a.reasons = new ArrayList<>(reasons);
        a.subject = subject;
        a.requestedAt = clock.instant().toString();
        run.approvals.add(a);
        return a;
    }

    private static void addFeedback(NodeRun n, List<String> feedback) {
        Set<String> merged = new LinkedHashSet<>(n.feedback);
        merged.addAll(feedback);
        List<String> list = new ArrayList<>(merged);
        n.feedback = list.size() > 8 ? new ArrayList<>(list.subList(list.size() - 8, list.size())) : list;
    }

    private String waitingSummary(RunState run) {
        List<String> parts = new ArrayList<>();
        run.approvals.stream().filter(a -> a.status == ApprovalRequest.Status.PENDING)
                .forEach(a -> parts.add(a.id + " (" + a.stageId + ", role " + a.requiredRole + ")"));
        run.questions.stream().filter(Question::isOpen).forEach(q -> parts.add(q.id + " (" + q.stageId + ")"));
        return String.join(", ", parts);
    }

    private AuditLog audit(RunState run) {
        return AuditLog.forRun(store.runDir(run.runId), run.runId, clock);
    }

    private GuardedWorkspace workspace(RunState run) {
        return new GuardedWorkspace(Path.of(run.cfg("workspace", store.runDir(run.runId).resolve("workspace").toString())),
                store.runDir(run.runId).resolve("journals"));
    }

    private void say(String line) {
        console.accept(line);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ');
        return one.length() > 160 ? one.substring(0, 160) + "…" : one;
    }
}
