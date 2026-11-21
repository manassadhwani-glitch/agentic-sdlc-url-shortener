package com.example.sdlc;

import com.example.sdlc.agents.Agent;
import com.example.sdlc.agents.Catalog;
import com.example.sdlc.agents.DesignAgent;
import com.example.sdlc.agents.DocsAgent;
import com.example.sdlc.agents.ImplementationAgent;
import com.example.sdlc.agents.MigrationCheckAgent;
import com.example.sdlc.agents.PlannerAgent;
import com.example.sdlc.agents.ReleaseAgent;
import com.example.sdlc.agents.RequirementsAgent;
import com.example.sdlc.agents.SecurityReviewAgent;
import com.example.sdlc.agents.TestDesignAgent;
import com.example.sdlc.agents.TestRunnerAgent;
import com.example.sdlc.approval.ApprovalService;
import com.example.sdlc.approval.ApproverRegistry;
import com.example.sdlc.audit.AuditLog;
import com.example.sdlc.engine.Engine;
import com.example.sdlc.gates.Gates;
import com.example.sdlc.json.Json;
import com.example.sdlc.llm.AnthropicClient;
import com.example.sdlc.llm.LlmClient;
import com.example.sdlc.llm.OfflineLlm;
import com.example.sdlc.model.ApprovalRequest;
import com.example.sdlc.model.ArtifactMeta;
import com.example.sdlc.model.Question;
import com.example.sdlc.model.RunState;
import com.example.sdlc.model.RunStatus;
import com.example.sdlc.policy.PolicyEngine;
import com.example.sdlc.report.Metrics;
import com.example.sdlc.report.ReportWriter;
import com.example.sdlc.store.ArtifactStore;
import com.example.sdlc.store.RunStore;
import com.example.sdlc.tools.TestRunner;
import com.example.sdlc.workflow.WorkflowLoader;
import com.example.sdlc.workspace.GuardedWorkspace;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Command-line interface.
 *
 * <pre>
 * demo    &lt;scenario.json&gt;                   run a scenario end to end; scripted reviewers answer each checkpoint
 * run     --scenario &lt;scenario.json&gt; | --requirement "text" [--source dir]   start a run (pauses at checkpoints)
 * status  &lt;runId&gt;                           show stages, pending approvals and questions
 * approve &lt;runId&gt; &lt;approvalId&gt; --as &lt;user&gt; [--comment "..."]
 * reject  &lt;runId&gt; &lt;approvalId&gt; --as &lt;user&gt; --comment "..."
 * answer  &lt;runId&gt; &lt;questionId&gt; --as &lt;user&gt; "answer or option number"
 * amend   &lt;runId&gt; --as &lt;user&gt; "new requirement text"        (triggers re-planning on resume)
 * resume  &lt;runId&gt;
 * stop    &lt;runId&gt; [--reason "..."]                           kill switch (safe-stop, resumable)
 * report  &lt;runId&gt; | verify-audit &lt;runId&gt; | list | metrics
 * </pre>
 * Common options: --runs-dir runs  --llm auto|off  --parallelism 4  --test-command "..."  --approvers file
 */
public final class Main {

    private final PrintStream out;
    private final Map<String, String> opts = new LinkedHashMap<>();
    private final List<String> args = new ArrayList<>();
    private final Clock clock = Clock.systemUTC();

    Main(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] argv) {
        int code;
        try {
            // Explicit UTF-8 so the status symbols render the same on every OS terminal.
            code = new Main(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8)).execute(argv);
        } catch (IllegalArgumentException | IllegalStateException | ApprovalService.ApprovalException e) {
            System.err.println("error: " + e.getMessage());
            code = 2;
        }
        System.exit(code);
    }

    int execute(String[] argv) {
        if (argv.length == 0) {
            out.println(usage());
            return 1;
        }
        String cmd = argv[0];
        for (int i = 1; i < argv.length; i++) {
            if (argv[i].startsWith("--")) {
                String key = argv[i].substring(2);
                String value = i + 1 < argv.length && !argv[i + 1].startsWith("--") ? argv[++i] : "true";
                opts.merge(key, value, (a, b) -> a + "," + b);
            } else {
                args.add(argv[i]);
            }
        }
        RunStore store = new RunStore(Path.of(opts.getOrDefault("runs-dir", "runs")), clock);
        return switch (cmd) {
            case "demo" -> demo(store);
            case "run" -> run(store);
            case "status" -> status(store, store.load(arg(0, "runId")));
            case "approve", "reject" -> decide(store, cmd.equals("approve"));
            case "answer" -> answer(store);
            case "amend" -> amend(store);
            case "resume" -> afterEngine(store, engine(store).resume(arg(0, "runId")));
            case "stop" -> stop(store);
            case "report" -> report(store, store.load(arg(0, "runId")));
            case "verify-audit" -> verify(store);
            case "list" -> list(store);
            case "metrics" -> metrics(store);
            default -> {
                out.println(usage());
                yield 1;
            }
        };
    }

    // ------------------------------------------------------------------ commands

    private int run(RunStore store) {
        RunState run = newRun(store, opts.containsKey("scenario") ? loadScenario(Path.of(opts.get("scenario"))) : adhocScenario());
        return afterEngine(store, engine(store).start(run, String.valueOf(run.config.remove("_requirement"))));
    }

    private int demo(RunStore store) {
        Path scenarioFile = Path.of(arg(0, "scenario.json"));
        Map<String, Object> scenario = loadScenario(scenarioFile);
        List<Map<String, Object>> script = new ArrayList<>();
        for (Object o : Json.list(scenario.get("script"))) {
            script.add(Json.obj(o));
        }
        RunState run = newRun(store, scenario);
        out.println("══ " + Json.str(scenario, "name") + ": " + Json.str(scenario, "description", ""));
        Engine engine = engine(store);
        run = engine.start(run, String.valueOf(run.config.remove("_requirement")));
        ApprovalService approvals = new ApprovalService(registry(), clock);
        int guard = 0;
        while (run.status == RunStatus.PAUSED && guard++ < 20) {
            boolean acted = false;
            while (!script.isEmpty() && applyScripted(run, script.get(0), approvals, store)) {
                script.remove(0);
                acted = true;
            }
            if (!acted) {
                out.println("No scripted decision matches the pending checkpoint; continue manually:");
                printPending(run);
                break;
            }
            store.save(run);
            run = engine.resume(run.runId);
        }
        return afterEngine(store, run);
    }

    /** Applies one scripted human action if it matches what the run is waiting for. */
    private boolean applyScripted(RunState run, Map<String, Object> step, ApprovalService approvals, RunStore store) {
        String type = Json.str(step, "type");
        String who = Json.str(step, "as");
        switch (type) {
            case "approve", "reject" -> {
                String stage = Json.str(step, "stage");
                Optional<ApprovalRequest> pending = ApprovalService.pending(run).stream()
                        .filter(a -> a.stageId.equals(stage)).filter(a -> registry().hasRole(who, a.requiredRole)).findFirst();
                if (pending.isEmpty()) {
                    return false;
                }
                approvals.decide(run, pending.get().id, who, type.equals("approve"), Json.str(step, "comment"));
                out.println("  👤 " + who + " " + type + "s " + pending.get().id + " (" + stage + ", " + pending.get().requiredRole + ")"
                        + (step.get("comment") == null ? "" : ": \"" + Json.str(step, "comment") + "\""));
                return true;
            }
            case "answer" -> {
                Optional<Question> q = run.questions.stream().filter(x -> x.isOpen() && x.id.equals(Json.str(step, "question"))).findFirst();
                if (q.isEmpty()) {
                    return false;
                }
                answerQuestion(q.get(), who, Json.str(step, "answer"));
                out.println("  👤 " + who + " answers " + q.get().id + ": " + q.get().answer);
                return true;
            }
            case "amend" -> {
                if (run.status != RunStatus.PAUSED || !Json.str(step, "whenStage", "").isEmpty()
                        && ApprovalService.pending(run).stream().noneMatch(a -> a.stageId.equals(Json.str(step, "whenStage")))) {
                    return false;
                }
                amendRequirement(store, run, who, Json.str(step, "requirement"));
                out.println("  👤 " + who + " amends the requirement: \"" + Json.str(step, "requirement") + "\"");
                return true;
            }
            default -> throw new IllegalArgumentException("unknown script step " + type);
        }
    }

    private int decide(RunStore store, boolean approve) {
        RunState run = store.load(arg(0, "runId"));
        String who = required("as");
        ApprovalRequest a = new ApprovalService(registry(), clock).decide(run, arg(1, "approvalId"), who, approve, opts.get("comment"));
        store.save(run);
        out.println(a.id + " " + a.status + " by " + who + ". Run `resume " + run.runId + "` to continue.");
        return 0;
    }

    private int answer(RunStore store) {
        RunState run = store.load(arg(0, "runId"));
        String qid = arg(1, "questionId");
        Question q = run.questions.stream().filter(x -> x.id.equals(qid) && x.isOpen()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no open question " + qid));
        String who = required("as");
        if (!registry().isKnown(who)) {
            throw new IllegalArgumentException("unknown user " + who);
        }
        answerQuestion(q, who, String.join(" ", args.subList(2, args.size())));
        store.save(run);
        out.println(qid + " answered. Run `resume " + run.runId + "` to continue.");
        return 0;
    }

    private int amend(RunStore store) {
        RunState run = store.load(arg(0, "runId"));
        amendRequirement(store, run, required("as"), String.join(" ", args.subList(1, args.size())));
        store.save(run);
        out.println("Requirement amended. Affected stages are re-planned on `resume " + run.runId + "`.");
        return 0;
    }

    private int stop(RunStore store) {
        String runId = arg(0, "runId");
        try {
            Files.writeString(store.runDir(runId).resolve("STOP"), opts.getOrDefault("reason", "requested by operator"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        RunState run = store.load(runId);
        if (run.status != RunStatus.RUNNING) {
            run.status = RunStatus.STOPPED;
            run.stopReason = "operator kill switch";
            store.save(run);
            AuditLog.forRun(store.runDir(runId), runId, clock).append("run.stopped", "operator", null, Map.of("reason", run.stopReason));
        }
        out.println("Kill switch set for " + runId + ". A running engine will safe-stop at the next step.");
        return 0;
    }

    private int verify(RunStore store) {
        String runId = arg(0, "runId");
        AuditLog.VerifyResult r = AuditLog.forRun(store.runDir(runId), runId, clock).verify();
        out.println((r.ok() ? "OK: " : "TAMPERED: ") + r.message());
        return r.ok() ? 0 : 3;
    }

    private int list(RunStore store) {
        for (String id : store.list()) {
            RunState r = store.load(id);
            out.printf("%-58s %-10s %s%n", id, r.status, r.scenario);
        }
        return 0;
    }

    private int metrics(RunStore store) {
        int total = 0;
        int succeeded = 0;
        long retries = 0;
        long rollbacks = 0;
        long mttrSum = 0;
        int mttrN = 0;
        for (String id : store.list()) {
            RunState r = store.load(id);
            if (!r.status.isTerminal()) {
                continue;
            }
            total++;
            succeeded += r.status == RunStatus.SUCCEEDED ? 1 : 0;
            Map<String, Object> m = Metrics.compute(r, AuditLog.forRun(store.runDir(id), id, clock).readAll());
            retries += Json.num(m, "retries", 0);
            rollbacks += Json.num(m, "rollbacks", 0);
            if (m.get("mttrMs") != null) {
                mttrSum += Json.num(m, "mttrMs", 0);
                mttrN++;
            }
        }
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("completedRuns", total);
        agg.put("runSuccessRate", total == 0 ? null : (double) succeeded / total);
        agg.put("retries", retries);
        agg.put("rollbacks", rollbacks);
        agg.put("meanMttrMs", mttrN == 0 ? null : mttrSum / mttrN);
        out.println(Json.pretty(agg));
        return 0;
    }

    private int report(RunStore store, RunState run) {
        Path p = new ReportWriter(store, clock).write(run);
        out.println("report: " + p.toAbsolutePath());
        return 0;
    }

    private int status(RunStore store, RunState run) {
        out.println(run.runId + "  " + run.status + (run.stopReason == null ? "" : "  (" + run.stopReason + ")"));
        run.stages.forEach(s -> {
            var n = run.nodes.get(s.id);
            out.printf("  %-16s %-24s attempts=%d%s%n", s.id, n.state, n.totalAttempts,
                    "planner".equals(s.addedBy) ? "  [added by planner]" : "");
        });
        printPending(run);
        return 0;
    }

    // ------------------------------------------------------------------ helpers

    private int afterEngine(RunStore store, RunState run) {
        Path report = new ReportWriter(store, clock).write(run);
        out.println();
        status(store, run);
        out.println("report: " + report.toAbsolutePath());
        return switch (run.status) {
            case SUCCEEDED -> 0;
            case PAUSED -> 10;
            case STOPPED -> 11;
            default -> 1;
        };
    }

    private void printPending(RunState run) {
        for (ApprovalRequest a : ApprovalService.pending(run)) {
            out.println("  ⏸ " + a.id + " [" + a.kind + "] stage=" + a.stageId + " role=" + a.requiredRole + " risk=" + a.risk);
            a.reasons.forEach(r -> out.println("       · " + r));
            out.println("       approve " + run.runId + " " + a.id + " --as <user with role " + a.requiredRole + ">");
        }
        for (Question q : run.questions) {
            if (q.isOpen()) {
                out.println("  ? " + q.id + ": " + q.text);
                for (int i = 0; i < q.options.size(); i++) {
                    out.println("       " + (i + 1) + ") " + q.options.get(i));
                }
                out.println("       answer " + run.runId + " " + q.id + " --as <user> \"<option number or text>\"");
            }
        }
    }

    private void answerQuestion(Question q, String who, String answer) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("answer must not be empty");
        }
        q.answer = answer.trim();
        q.answeredBy = who;
        q.answeredAt = clock.instant().toString();
    }

    private void amendRequirement(RunStore store, RunState run, String who, String text) {
        if (!registry().isKnown(who)) {
            throw new IllegalArgumentException("unknown user " + who);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("requirement text must not be empty");
        }
        ArtifactMeta meta = new ArtifactStore(store, clock).write(run, "requirement", text, "human", 0, "human", Map.of()).meta();
        AuditLog.forRun(store.runDir(run.runId), run.runId, clock)
                .append("requirement.amended", "human:" + who, null, Map.of("artifact", meta.ref(), "sha256", meta.sha256));
    }

    private RunState newRun(RunStore store, Map<String, Object> scenario) {
        RunState run = new RunState();
        run.scenario = Json.str(scenario, "name", "adhoc");
        run.runId = store.newRunId(run.scenario);
        run.initiator = opts.getOrDefault("initiator", Json.str(scenario, "initiator", "manas"));
        if (!registry().isKnown(run.initiator)) {
            throw new IllegalArgumentException("unknown initiator " + run.initiator);
        }
        run.workflow = Json.str(scenario, "workflow", "sdlc-default");
        run.stages = WorkflowLoader.load(run.workflow);
        Path runDir = store.runDir(run.runId);
        Path workspace = runDir.resolve("workspace").toAbsolutePath();
        Path baseline = runDir.resolve("baseline").toAbsolutePath();
        try {
            Files.createDirectories(workspace);
            Files.createDirectories(baseline);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String source = Json.str(scenario, "source");
        if (source != null) {
            GuardedWorkspace.copyTree(Path.of(source), workspace);
            GuardedWorkspace.copyTree(Path.of(source), baseline);
        }
        run.config.put("workspace", workspace.toString());
        run.config.put("baselineDir", baseline.toString());
        if (scenario.get("referenceDir") != null) {
            run.config.put("referenceDir", Path.of(Json.str(scenario, "referenceDir")).toAbsolutePath().toString());
        }
        run.config.put("testCommand", opts.getOrDefault("test-command", Json.str(scenario, "testCommand", "mvn -q -B test")));
        String build = opts.getOrDefault("build-command", Json.str(scenario, "buildCommand"));
        if (build != null) {
            run.config.put("buildCommand", build);
        }
        List<String> faults = new ArrayList<>(Json.strings(scenario.get("faults")));
        if (opts.containsKey("fault")) {
            faults.addAll(List.of(opts.get("fault").split(",")));
        }
        run.config.put("faults", faults);
        run.config.put("maxTotalAttempts", 40L);
        run.config.put("maxSessionMinutes", 60L);
        run.config.put("maxLlmCalls", 60L);
        run.config.put("_requirement", Json.str(scenario, "requirement"));
        return run;
    }

    private Map<String, Object> loadScenario(Path file) {
        try {
            Map<String, Object> s = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            Path repoRoot = file.toAbsolutePath().getParent().getParent().getParent();
            for (String key : List.of("source", "referenceDir")) {
                if (s.get(key) != null) {
                    s.put(key, repoRoot.resolve(Json.str(s, key)).normalize().toString());
                }
            }
            return s;
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read scenario " + file + ": " + e.getMessage());
        }
    }

    private Map<String, Object> adhocScenario() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", "adhoc");
        s.put("requirement", required("requirement"));
        if (opts.containsKey("source")) {
            s.put("source", Path.of(opts.get("source")).toAbsolutePath().toString());
        }
        if (opts.containsKey("reference")) {
            s.put("referenceDir", Path.of(opts.get("reference")).toAbsolutePath().toString());
        }
        return s;
    }

    private Engine engine(RunStore store) {
        PolicyEngine policy = PolicyEngine.defaults();
        LlmClient llm = "off".equals(opts.getOrDefault("llm", "auto")) ? new OfflineLlm() : AnthropicClient.fromEnvironment(60);
        Map<String, Agent> agents = new LinkedHashMap<>();
        for (Agent a : List.of(new RequirementsAgent(), new DesignAgent(), new PlannerAgent(), new ImplementationAgent(),
                new TestDesignAgent(), new TestRunnerAgent(), new MigrationCheckAgent(), new SecurityReviewAgent(policy),
                new DocsAgent(), new ReleaseAgent())) {
            agents.put(a.name(), a);
        }
        int parallelism = Integer.parseInt(opts.getOrDefault("parallelism", "4"));
        return new Engine(store, agents, new Gates(policy), policy, llm, Catalog.load(), new TestRunner(), clock,
                parallelism, out::println);
    }

    private ApproverRegistry registry() {
        return ApproverRegistry.load(opts.containsKey("approvers") ? Path.of(opts.get("approvers")) : null);
    }

    private String arg(int i, String name) {
        if (args.size() <= i) {
            throw new IllegalArgumentException("missing argument <" + name + ">");
        }
        return args.get(i);
    }

    private String required(String opt) {
        String v = opts.get(opt);
        if (v == null || v.equals("true")) {
            throw new IllegalArgumentException("missing --" + opt);
        }
        return v;
    }

    private static String usage() {
        return """
                Agentic SDLC orchestrator
                  demo    <scenario.json>
                  run     --scenario <scenario.json> | --requirement "text" [--source dir] [--reference dir]
                  status  <runId>
                  approve <runId> <approvalId> --as <user> [--comment "..."]
                  reject  <runId> <approvalId> --as <user> --comment "..."
                  answer  <runId> <questionId> --as <user> "<option number or text>"
                  amend   <runId> --as <user> "<new requirement>"
                  resume  <runId>
                  stop    <runId> [--reason "..."]
                  report | verify-audit <runId>     list     metrics
                options: --runs-dir runs --llm auto|off --parallelism 4 --test-command "..." --fault stage:id --approvers file
                """;
    }
}
