package com.example.sdlc.model;

import com.example.sdlc.json.Json;
import com.example.sdlc.json.JsonWritable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime state of one stage within a run. Persisted after every transition. */
public final class NodeRun implements JsonWritable {

    public String stageId;
    public NodeState state = NodeState.PENDING;
    /** Attempts in the current execution (reset on invalidation / rework). */
    public int attempts;
    public int totalAttempts;
    public int reworks;
    public int invalidations;
    public String firstStartedAt;
    public String firstFailedAt;
    public String finishedAt;
    public long nextAttemptAtMs;
    public String lastError;
    /** Feedback for the next attempt: test failures, policy findings, reviewer comments, answers. */
    public List<String> feedback = new ArrayList<>();
    /** Input artifact → version this node last consumed (lineage, used for invalidation). */
    public Map<String, Integer> consumed = new LinkedHashMap<>();
    /** Journals applied to the workspace by this node and not yet rolled back, oldest first. */
    public List<String> appliedJournals = new ArrayList<>();
    public String pendingApprovalId;
    /** Set on resume: "apply-approved" = apply the human-approved change set instead of re-running the agent. */
    public String pendingAction;
    public String mode;
    public boolean usedFallback;
    public List<Map<String, Object>> attemptLog = new ArrayList<>();

    public NodeRun() {
    }

    public NodeRun(String stageId) {
        this.stageId = stageId;
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stageId", stageId);
        m.put("state", state);
        m.put("attempts", attempts);
        m.put("totalAttempts", totalAttempts);
        m.put("reworks", reworks);
        m.put("invalidations", invalidations);
        m.put("firstStartedAt", firstStartedAt);
        m.put("firstFailedAt", firstFailedAt);
        m.put("finishedAt", finishedAt);
        m.put("nextAttemptAtMs", nextAttemptAtMs);
        m.put("lastError", lastError);
        m.put("feedback", feedback);
        m.put("consumed", consumed);
        m.put("appliedJournals", appliedJournals);
        m.put("pendingApprovalId", pendingApprovalId);
        m.put("pendingAction", pendingAction);
        m.put("mode", mode);
        m.put("usedFallback", usedFallback);
        m.put("attemptLog", attemptLog);
        return m;
    }

    public static NodeRun fromJson(Map<String, Object> m) {
        NodeRun n = new NodeRun(Json.str(m, "stageId"));
        n.state = NodeState.valueOf(Json.str(m, "state", "PENDING"));
        n.attempts = (int) Json.num(m, "attempts", 0);
        n.totalAttempts = (int) Json.num(m, "totalAttempts", 0);
        n.reworks = (int) Json.num(m, "reworks", 0);
        n.invalidations = (int) Json.num(m, "invalidations", 0);
        n.firstStartedAt = Json.str(m, "firstStartedAt");
        n.firstFailedAt = Json.str(m, "firstFailedAt");
        n.finishedAt = Json.str(m, "finishedAt");
        n.nextAttemptAtMs = Json.num(m, "nextAttemptAtMs", 0);
        n.lastError = Json.str(m, "lastError");
        n.feedback = Json.strings(m.get("feedback"));
        Json.obj(m.get("consumed")).forEach((k, v) -> n.consumed.put(k, ((Number) v).intValue()));
        n.appliedJournals = Json.strings(m.get("appliedJournals"));
        n.pendingApprovalId = Json.str(m, "pendingApprovalId");
        n.pendingAction = Json.str(m, "pendingAction");
        n.mode = Json.str(m, "mode");
        n.usedFallback = Json.bool(m, "usedFallback", false);
        for (Object o : Json.list(m.get("attemptLog"))) {
            n.attemptLog.add(Json.obj(o));
        }
        return n;
    }
}
