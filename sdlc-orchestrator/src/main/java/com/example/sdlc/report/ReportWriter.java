package com.example.sdlc.report;

import com.example.sdlc.audit.AuditLog;
import com.example.sdlc.json.Json;
import com.example.sdlc.model.ApprovalRequest;
import com.example.sdlc.model.ArtifactMeta;
import com.example.sdlc.model.NodeRun;
import com.example.sdlc.model.Question;
import com.example.sdlc.model.RunState;
import com.example.sdlc.model.StageDef;
import com.example.sdlc.store.RunStore;
import com.example.sdlc.workflow.WorkflowValidator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Writes {@code report.md} and {@code metrics.json} for a run, and exports the change set as a patch. */
public final class ReportWriter {

    private static final Set<String> DECISION_EVENTS = Set.of("decision");
    private static final Set<String> REPLAN_EVENTS = Set.of("replan.node-added", "replan.edge-added", "replan.invalidated",
            "replan.rework", "replan.rejected");

    private final RunStore store;
    private final Clock clock;

    public ReportWriter(RunStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public Path write(RunState run) {
        Path dir = store.runDir(run.runId);
        AuditLog audit = AuditLog.forRun(dir, run.runId, clock);
        List<Map<String, Object>> events = audit.readAll();
        AuditLog.VerifyResult verify = audit.verify();
        Map<String, Object> metrics = Metrics.compute(run, events);
        StringBuilder r = new StringBuilder();
        r.append("# Run report: `").append(run.runId).append("`\n\n");
        r.append("| | |\n|---|---|\n");
        r.append("| Scenario | ").append(run.scenario).append(" |\n");
        r.append("| Status | **").append(run.status).append("**").append(run.stopReason == null ? "" : " (" + run.stopReason + ")").append(" |\n");
        r.append("| Initiator | ").append(run.initiator).append(" |\n");
        r.append("| Plan version | ").append(run.planVersion).append(" |\n");
        r.append("| Audit chain | ").append(verify.ok() ? "✅ " : "❌ ").append(verify.message()).append(" |\n\n");

        r.append("## Stage graph\n\n```\n");
        for (List<String> wave : WorkflowValidator.waves(run.stages)) {
            r.append(wave.size() > 1 ? "┬ parallel\n" : "");
            for (String id : wave) {
                NodeRun n = run.nodes.get(id);
                StageDef s = run.stage(id);
                long ms = n.attemptLog.stream().mapToLong(a -> Json.num(a, "durationMs", 0)).sum();
                r.append(wave.size() > 1 ? "├─ " : "── ").append(icon(n)).append(' ').append(String.format("%-16s", id))
                        .append(String.format("%-22s", n.state)).append(" attempts=").append(n.totalAttempts)
                        .append(n.reworks > 0 ? " reworks=" + n.reworks : "").append(n.invalidations > 0 ? " invalidated=" + n.invalidations : "")
                        .append("  ").append(ms).append("ms").append("planner".equals(s.addedBy) ? "  [added by planner]" : "")
                        .append('\n');
            }
        }
        r.append("```\n\n");

        if (!run.questions.isEmpty()) {
            r.append("## Clarifications\n\n| Id | Question | Answer | By |\n|---|---|---|---|\n");
            for (Question q : run.questions) {
                r.append("| ").append(q.id).append(" | ").append(q.text).append(" | ").append(q.answer == null ? "_open_" : q.answer)
                        .append(" | ").append(q.answeredBy == null ? "" : q.answeredBy).append(" |\n");
            }
            r.append('\n');
        }
        if (!run.approvals.isEmpty()) {
            r.append("## Human checkpoints\n\n| Id | Stage | Kind | Role | Risk | Status | By | Comment | Why |\n|---|---|---|---|---|---|---|---|---|\n");
            for (ApprovalRequest a : run.approvals) {
                r.append("| ").append(a.id).append(" | ").append(a.stageId).append(" | ").append(a.kind).append(" | ")
                        .append(a.requiredRole).append(" | ").append(a.risk).append(" | ").append(a.status).append(" | ")
                        .append(a.decidedBy == null ? "" : a.decidedBy).append(" | ").append(a.comment == null ? "" : a.comment)
                        .append(" | ").append(String.join("<br>", a.reasons)).append(" |\n");
            }
            r.append('\n');
        }

        r.append("## Re-planning and recovery events\n\n");
        boolean any = false;
        for (Map<String, Object> e : events) {
            String type = Json.str(e, "type");
            if (REPLAN_EVENTS.contains(type) || type.equals("rollback") || type.equals("stage.fallback")
                    || type.equals("stage.retry-scheduled") || type.equals("run.stopped") || type.equals("stage.fatal")) {
                r.append("- `").append(Json.str(e, "ts")).append("` **").append(type).append("** ")
                        .append(Json.str(e, "stage", "")).append(": ").append(summarise(Json.obj(e.get("data")))).append('\n');
                any = true;
            }
        }
        if (!any) {
            r.append("_none_\n");
        }

        r.append("\n## Policy findings\n\n");
        any = false;
        for (Map<String, Object> e : events) {
            if ("policy.finding".equals(e.get("type"))) {
                Map<String, Object> d = Json.obj(e.get("data"));
                r.append("- ").append(Json.str(e, "stage")).append(": `").append(Json.str(d, "ruleId")).append("` ")
                        .append(Json.str(d, "effect")).append(": ").append(Json.str(d, "message"))
                        .append(d.get("path") == null ? "" : " (" + Json.str(d, "path") + ")").append('\n');
                any = true;
            }
        }
        for (Map<String, Object> e : events) {
            if ("gate".equals(e.get("type")) && "compliance-text".equals(Json.obj(e.get("data")).get("gate"))
                    && !Json.bool(Json.obj(e.get("data")), "passed", true)) {
                r.append("- ").append(Json.str(e, "stage")).append(": compliance gate: ")
                        .append(String.join("; ", Json.strings(Json.obj(e.get("data")).get("details")))).append('\n');
                any = true;
            }
        }
        if (!any) {
            r.append("_none_\n");
        }

        r.append("\n## Decision lineage\n\n");
        for (Map<String, Object> e : events) {
            if (DECISION_EVENTS.contains(Json.str(e, "type"))) {
                Map<String, Object> d = Json.obj(e.get("data"));
                r.append("- **").append(Json.str(e, "stage")).append("**: ").append(Json.str(d, "decision"))
                        .append(" _(because ").append(Json.str(d, "rationale")).append(")_ inputs=").append(Json.write(d.get("inputs"))).append('\n');
            }
        }

        r.append("\n## Metrics\n\n| Metric | Value |\n|---|---|\n");
        metrics.forEach((k, v) -> r.append("| ").append(k).append(" | ").append(v instanceof Map ? Json.write(v) : String.valueOf(v)).append(" |\n"));

        r.append("\n## Artifacts (versioned, content-addressed)\n\n| Artifact | SHA-256 | Produced by | Mode | Inputs |\n|---|---|---|---|---|\n");
        for (List<ArtifactMeta> versions : run.artifacts.values()) {
            for (ArtifactMeta a : versions) {
                r.append("| ").append(a.ref()).append(" | `").append(a.sha256, 0, 12).append("` | ").append(a.producedBy)
                        .append(" (attempt ").append(a.attempt).append(") | ").append(a.mode).append(" | ").append(Json.write(a.inputs)).append(" |\n");
            }
        }
        try {
            Path report = dir.resolve("report.md");
            Files.writeString(report, r.toString(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("metrics.json"), Json.pretty(metrics), StandardCharsets.UTF_8);
            exportPatch(run, dir);
            return report;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code changes.patch} (baseline vs workspace) using git if available. Humans review and merge it. */
    private void exportPatch(RunState run, Path dir) {
        Object baseline = run.config.get("baselineDir");
        Object workspace = run.config.get("workspace");
        if (baseline == null || workspace == null) {
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--no-index", "--no-color", "--", baseline.toString(), workspace.toString());
            pb.redirectErrorStream(true);
            Path out = dir.resolve("changes.patch");
            pb.redirectOutput(out.toFile());
            Process p = pb.start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (IOException e) {
            // git not installed: the patch export is a convenience, the journals are the source of truth
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String icon(NodeRun n) {
        return switch (n.state) {
            case SUCCEEDED -> "✔";
            case FAILED -> "✖";
            case AWAITING_APPROVAL, AWAITING_CLARIFICATION -> "⏸";
            case CANCELLED -> "■";
            case SKIPPED -> "–";
            default -> "·";
        };
    }

    private static String summarise(Map<String, Object> data) {
        String s = Json.write(data);
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }
}
