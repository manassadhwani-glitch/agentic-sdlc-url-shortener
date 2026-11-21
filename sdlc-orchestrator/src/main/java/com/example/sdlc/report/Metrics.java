package com.example.sdlc.report;

import com.example.sdlc.json.Json;
import com.example.sdlc.model.ApprovalRequest;
import com.example.sdlc.model.NodeRun;
import com.example.sdlc.model.NodeState;
import com.example.sdlc.model.RunState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reliability metrics computed from the run state and the audit trail (the audit log is the source of truth, so
 * these numbers can be recomputed and checked independently).
 */
public final class Metrics {

    private Metrics() {
    }

    public static Map<String, Object> compute(RunState run, List<Map<String, Object>> audit) {
        Map<String, Object> m = new LinkedHashMap<>();
        long attempts = 0;
        long okAttempts = 0;
        int stagesNeverFailed = 0;
        Map<String, Long> stageMs = new LinkedHashMap<>();
        long agentMs = 0;
        for (NodeRun n : run.nodes.values()) {
            boolean failed = false;
            for (Map<String, Object> a : n.attemptLog) {
                attempts++;
                String outcome = String.valueOf(a.get("outcome"));
                if (outcome.equals("FAILED") || outcome.equals("FATAL")) {
                    failed = true;
                } else {
                    okAttempts++;
                }
                long d = Json.num(a, "durationMs", 0);
                stageMs.merge(n.stageId, d, Long::sum);
                agentMs += d;
            }
            if (!failed && !n.attemptLog.isEmpty()) {
                stagesNeverFailed++;
            }
        }
        long executedStages = run.nodes.values().stream().filter(n -> !n.attemptLog.isEmpty()).count();
        m.put("runStatus", run.status);
        m.put("stages", run.nodes.size());
        m.put("stagesSucceeded", run.nodes.values().stream().filter(n -> n.state == NodeState.SUCCEEDED).count());
        m.put("attempts", attempts);
        m.put("attemptSuccessRate", ratio(okAttempts, attempts));
        m.put("firstPassYield", ratio(stagesNeverFailed, executedStages));
        m.put("retries", run.counter("retries"));
        m.put("retryRate", ratio(run.counter("retries"), attempts));
        m.put("fallbacks", run.counter("fallbacks"));
        m.put("rollbacks", run.counter("rollbacks"));
        m.put("rollbackRate", ratio(run.counter("rollbacks"), attempts));
        m.put("reworks", run.counter("reworks"));
        m.put("invalidations", run.counter("invalidations"));
        m.put("planVersion", run.planVersion);
        m.put("llmCalls", run.counter("llmCalls"));

        // MTTR: from a stage's first failing attempt to its next success (per incident), averaged.
        List<Long> recoveries = new ArrayList<>();
        Map<String, Instant> openIncident = new HashMap<>();
        for (Map<String, Object> e : audit) {
            String type = Json.str(e, "type");
            String stage = Json.str(e, "stage");
            Instant ts = Instant.parse(Json.str(e, "ts"));
            if ("stage.attempt".equals(type)) {
                String outcome = Json.str(Json.obj(e.get("data")), "outcome");
                if (("FAILED".equals(outcome) || "FATAL".equals(outcome)) && !openIncident.containsKey(stage)) {
                    openIncident.put(stage, ts);
                }
            } else if ("stage.succeeded".equals(type) && openIncident.containsKey(stage)) {
                recoveries.add(Duration.between(openIncident.remove(stage), ts).toMillis());
            }
        }
        m.put("incidents", recoveries.size() + openIncident.size());
        m.put("incidentsRecovered", recoveries.size());
        m.put("mttrMs", recoveries.isEmpty() ? null : recoveries.stream().mapToLong(Long::longValue).sum() / recoveries.size());

        long humanWaitMs = 0;
        int decided = 0;
        for (ApprovalRequest a : run.approvals) {
            if (a.decidedAt != null && a.requestedAt != null) {
                humanWaitMs += Duration.between(Instant.parse(a.requestedAt), Instant.parse(a.decidedAt)).toMillis();
                decided++;
            }
        }
        m.put("approvalsRequested", run.approvals.size());
        m.put("approvalsDecided", decided);
        m.put("approvalsRejected", run.approvals.stream().filter(a -> a.status == ApprovalRequest.Status.REJECTED).count());
        m.put("questionsAsked", run.questions.size());
        Instant start = run.createdAt == null ? null : Instant.parse(run.createdAt);
        Instant end = run.finishedAt != null ? Instant.parse(run.finishedAt)
                : audit.isEmpty() ? null : Instant.parse(Json.str(audit.get(audit.size() - 1), "ts"));
        m.put("endToEndLatencyMs", start == null || end == null ? null : Duration.between(start, end).toMillis());
        m.put("agentExecutionMs", agentMs);
        m.put("humanWaitMs", humanWaitMs);
        m.put("stageLatencyMs", stageMs);
        return m;
    }

    private static double ratio(long a, long b) {
        return b == 0 ? 1.0 : Math.round(1000.0 * a / b) / 1000.0;
    }
}
