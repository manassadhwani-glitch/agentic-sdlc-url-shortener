package com.example.sdlc.model;

import com.example.sdlc.json.Json;
import com.example.sdlc.json.JsonWritable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Complete, durable state of one run. Written to disk after every transition, so a run can pause for a human,
 * survive a crash or restart, and resume exactly where it stopped.
 */
public final class RunState implements JsonWritable {

    public String runId;
    public String scenario;
    public String initiator;
    public String workflow;
    public String createdAt;
    public String finishedAt;
    public RunStatus status = RunStatus.RUNNING;
    public String stopReason;
    public int planVersion = 1;
    public List<StageDef> stages = new ArrayList<>();
    public Map<String, NodeRun> nodes = new LinkedHashMap<>();
    public Map<String, List<ArtifactMeta>> artifacts = new LinkedHashMap<>();
    public List<ApprovalRequest> approvals = new ArrayList<>();
    public List<Question> questions = new ArrayList<>();
    /** Run configuration: workspace, test command, LLM mode, budgets, fault injection. */
    public Map<String, Object> config = new LinkedHashMap<>();
    public Map<String, Long> counters = new LinkedHashMap<>();
    /** Every journal applied to the workspace, in application order (saga compensation runs it in reverse). */
    public List<String> journalOrder = new ArrayList<>();

    public StageDef stage(String id) {
        return stages.stream().filter(s -> s.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown stage " + id));
    }

    public NodeRun node(String id) {
        NodeRun n = nodes.get(id);
        if (n == null) {
            throw new IllegalArgumentException("unknown node " + id);
        }
        return n;
    }

    public Optional<ArtifactMeta> latest(String artifact) {
        List<ArtifactMeta> versions = artifacts.get(artifact);
        return versions == null || versions.isEmpty() ? Optional.empty() : Optional.of(versions.get(versions.size() - 1));
    }

    public Optional<ApprovalRequest> approval(String id) {
        return approvals.stream().filter(a -> a.id.equals(id)).findFirst();
    }

    public long increment(String counter) {
        return counters.merge(counter, 1L, Long::sum);
    }

    public long counter(String counter) {
        return counters.getOrDefault(counter, 0L);
    }

    public String cfg(String key, String def) {
        Object v = config.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public long cfgLong(String key, long def) {
        Object v = config.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId);
        m.put("scenario", scenario);
        m.put("initiator", initiator);
        m.put("workflow", workflow);
        m.put("createdAt", createdAt);
        m.put("finishedAt", finishedAt);
        m.put("status", status);
        m.put("stopReason", stopReason);
        m.put("planVersion", planVersion);
        m.put("stages", stages);
        m.put("nodes", nodes);
        m.put("artifacts", artifacts);
        m.put("approvals", approvals);
        m.put("questions", questions);
        m.put("config", config);
        m.put("counters", counters);
        m.put("journalOrder", journalOrder);
        return m;
    }

    public static RunState fromJson(Map<String, Object> m) {
        RunState r = new RunState();
        r.runId = Json.str(m, "runId");
        r.scenario = Json.str(m, "scenario");
        r.initiator = Json.str(m, "initiator");
        r.workflow = Json.str(m, "workflow");
        r.createdAt = Json.str(m, "createdAt");
        r.finishedAt = Json.str(m, "finishedAt");
        r.status = RunStatus.valueOf(Json.str(m, "status", "RUNNING"));
        r.stopReason = Json.str(m, "stopReason");
        r.planVersion = (int) Json.num(m, "planVersion", 1);
        for (Object o : Json.list(m.get("stages"))) {
            r.stages.add(StageDef.fromJson(Json.obj(o)));
        }
        Json.obj(m.get("nodes")).forEach((k, v) -> r.nodes.put(k, NodeRun.fromJson(Json.obj(v))));
        Json.obj(m.get("artifacts")).forEach((k, v) -> {
            List<ArtifactMeta> versions = new ArrayList<>();
            for (Object o : Json.list(v)) {
                versions.add(ArtifactMeta.fromJson(Json.obj(o)));
            }
            r.artifacts.put(k, versions);
        });
        for (Object o : Json.list(m.get("approvals"))) {
            r.approvals.add(ApprovalRequest.fromJson(Json.obj(o)));
        }
        for (Object o : Json.list(m.get("questions"))) {
            r.questions.add(Question.fromJson(Json.obj(o)));
        }
        r.config = Json.obj(m.get("config"));
        Json.obj(m.get("counters")).forEach((k, v) -> r.counters.put(k, ((Number) v).longValue()));
        r.journalOrder = Json.strings(m.get("journalOrder"));
        return r;
    }
}
