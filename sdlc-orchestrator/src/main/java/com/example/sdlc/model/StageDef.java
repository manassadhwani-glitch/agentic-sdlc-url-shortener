package com.example.sdlc.model;

import com.example.sdlc.json.Json;
import com.example.sdlc.json.JsonWritable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative definition of one stage (graph node). Workflows are data (JSON), not code, so the graph can be
 * reviewed, validated before execution, and extended at runtime by the planner (dynamic re-planning).
 */
public final class StageDef implements JsonWritable {

    public String id;
    public String agent;
    public List<String> dependsOn = new ArrayList<>();
    /** Artifacts this stage may read. Agents get only these (least-privilege context). */
    public List<String> consumes = new ArrayList<>();
    /** Artifacts this stage must produce; checked by the required-outputs exit gate. */
    public List<String> produces = new ArrayList<>();
    public List<String> entryGates = new ArrayList<>();
    public List<String> exitGates = new ArrayList<>();
    public Risk risk = Risk.LOW;
    public int maxAttempts = 2;
    public long backoffMs = 200;
    /** "none" | "always": always = human sign-off after the exit gates pass. */
    public String approval = "none";
    public String approvalRole;
    public boolean mutatesWorkspace;
    /** Glob patterns (relative to the workspace) this stage may write. Enforced by GuardedWorkspace. */
    public List<String> writeScope = new ArrayList<>();
    /** On human rejection, re-run this stage (usually itself or an upstream design stage) with the feedback. */
    public String onReject;
    /**
     * Non-linear routing: if this stage fails terminally, roll back and re-run this upstream stage with the failure
     * as feedback (e.g. verify fails → back to implement), bounded by that stage's maxReworks.
     */
    public String onFailure;
    public int maxReworks = 2;
    public String addedBy = "workflow";
    public String reason;

    public boolean requiresSignoff() {
        return "always".equalsIgnoreCase(approval);
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("agent", agent);
        m.put("dependsOn", dependsOn);
        m.put("consumes", consumes);
        m.put("produces", produces);
        m.put("entryGates", entryGates);
        m.put("exitGates", exitGates);
        m.put("risk", risk);
        m.put("maxAttempts", maxAttempts);
        m.put("backoffMs", backoffMs);
        m.put("approval", approval);
        m.put("approvalRole", approvalRole);
        m.put("mutatesWorkspace", mutatesWorkspace);
        m.put("writeScope", writeScope);
        m.put("onReject", onReject);
        m.put("onFailure", onFailure);
        m.put("maxReworks", maxReworks);
        m.put("addedBy", addedBy);
        m.put("reason", reason);
        return m;
    }

    public static StageDef fromJson(Map<String, Object> m) {
        StageDef s = new StageDef();
        s.id = Json.str(m, "id");
        s.agent = Json.str(m, "agent");
        s.dependsOn = Json.strings(m.get("dependsOn"));
        s.consumes = Json.strings(m.get("consumes"));
        s.produces = Json.strings(m.get("produces"));
        s.entryGates = Json.strings(m.get("entryGates"));
        s.exitGates = Json.strings(m.get("exitGates"));
        s.risk = Risk.valueOf(Json.str(m, "risk", "LOW"));
        s.maxAttempts = (int) Json.num(m, "maxAttempts", 2);
        s.backoffMs = Json.num(m, "backoffMs", 200);
        s.approval = Json.str(m, "approval", "none");
        s.approvalRole = Json.str(m, "approvalRole");
        s.mutatesWorkspace = Json.bool(m, "mutatesWorkspace", false);
        s.writeScope = Json.strings(m.get("writeScope"));
        s.onReject = Json.str(m, "onReject");
        s.onFailure = Json.str(m, "onFailure");
        s.maxReworks = (int) Json.num(m, "maxReworks", 2);
        s.addedBy = Json.str(m, "addedBy", "workflow");
        s.reason = Json.str(m, "reason");
        return s;
    }
}
