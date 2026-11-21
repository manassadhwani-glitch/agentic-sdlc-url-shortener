package com.example.sdlc.model;

import com.example.sdlc.json.Json;
import com.example.sdlc.json.JsonWritable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A human checkpoint. Holds everything the reviewer needs plus who decided, in which role, and why. */
public final class ApprovalRequest implements JsonWritable {

    public enum Kind { CHANGE_SET, SIGNOFF }

    public enum Status { PENDING, APPROVED, REJECTED, SUPERSEDED }

    public String id;
    public String stageId;
    public Kind kind;
    public Risk risk;
    public String requiredRole;
    public List<String> reasons = new ArrayList<>();
    /** Artifact the reviewer is approving (e.g. proposed-changes@v2). */
    public String subject;
    public String requestedAt;
    public Status status = Status.PENDING;
    public String decidedBy;
    public String decidedRole;
    public String comment;
    public String decidedAt;

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("stageId", stageId);
        m.put("kind", kind);
        m.put("risk", risk);
        m.put("requiredRole", requiredRole);
        m.put("reasons", reasons);
        m.put("subject", subject);
        m.put("requestedAt", requestedAt);
        m.put("status", status);
        m.put("decidedBy", decidedBy);
        m.put("decidedRole", decidedRole);
        m.put("comment", comment);
        m.put("decidedAt", decidedAt);
        return m;
    }

    public static ApprovalRequest fromJson(Map<String, Object> m) {
        ApprovalRequest a = new ApprovalRequest();
        a.id = Json.str(m, "id");
        a.stageId = Json.str(m, "stageId");
        a.kind = Kind.valueOf(Json.str(m, "kind"));
        a.risk = Risk.valueOf(Json.str(m, "risk", "LOW"));
        a.requiredRole = Json.str(m, "requiredRole");
        a.reasons = Json.strings(m.get("reasons"));
        a.subject = Json.str(m, "subject");
        a.requestedAt = Json.str(m, "requestedAt");
        a.status = Status.valueOf(Json.str(m, "status", "PENDING"));
        a.decidedBy = Json.str(m, "decidedBy");
        a.decidedRole = Json.str(m, "decidedRole");
        a.comment = Json.str(m, "comment");
        a.decidedAt = Json.str(m, "decidedAt");
        return a;
    }
}
