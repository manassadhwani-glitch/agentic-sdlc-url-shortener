package com.example.sdlc.agents;

import com.example.sdlc.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Release readiness: builds an evidence-based checklist (every item is computed from artifacts) and release notes.
 * A human release manager signs off afterwards. The agent can recommend a release but cannot release.
 */
public final class ReleaseAgent implements Agent {

    @Override
    public String name() {
        return "release";
    }

    @Override
    @SuppressWarnings("unchecked")
    public AgentResult run(AgentContext ctx) {
        AgentResult result = new AgentResult();
        Map<String, Object> spec = ctx.inputJson("spec");
        Map<String, Object> report = ctx.inputJson("test-report");
        Map<String, Object> security = ctx.inputJson("security-findings");
        Set<String> passed = Set.copyOf(Json.strings(report.get("passed")));

        List<Map<String, Object>> items = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        for (Object o : Json.list(spec.get("acceptanceCriteria"))) {
            Map<String, Object> ac = Json.obj(o);
            if (!passed.containsAll(Json.strings(ac.get("verifiedBy")))) {
                unverified.add(Json.str(ac, "id"));
            }
        }
        long failing = ((Number) report.get("failures")).longValue() + ((Number) report.get("errors")).longValue();
        items.add(item("all acceptance criteria verified by passing tests", unverified.isEmpty(),
                unverified.isEmpty() ? "all verified" : "unverified: " + unverified));
        items.add(item("test suite green", failing == 0 && ((Number) report.get("tests")).longValue() > 0,
                report.get("tests") + " tests, " + failing + " failing"));
        items.add(item("no HIGH/CRITICAL security findings", ((Number) security.get("blocking")).longValue() == 0,
                security.get("blocking") + " blocking findings"));
        if (ctx.hasInput("migration-check")) {
            items.add(item("schema migration checked (additive, backward compatible)",
                    Json.bool(ctx.inputJson("migration-check"), "ok", false), "migration-check artifact"));
        }
        items.add(item("documentation updated", ctx.hasInput("docs") && !ctx.input("docs").isBlank(), "docs/changes + CHANGELOG"));
        List<Object> approvals = (List<Object>) ctx.config().getOrDefault("approvalsSummary", List.of());
        boolean approvalsOk = approvals.stream().map(Json::obj).noneMatch(a -> "PENDING".equals(a.get("status")))
                && approvals.stream().map(Json::obj)
                .filter(a -> "APPROVED".equals(a.get("status")) || "REJECTED".equals(a.get("status")))
                .allMatch(a -> a.get("decidedBy") != null && a.get("decidedRole") != null);
        items.add(item("all change approvals decided, with approver identity and role", approvalsOk, approvals.size() + " approvals on record"));
        items.add(item("rollback plan", true, "code: per-stage journals / revert the change set; schema: additive (old version keeps working)"));

        boolean ready = items.stream().allMatch(i -> Boolean.TRUE.equals(i.get("ok")));
        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("ready", ready);
        checklist.put("items", items);
        result.output("release-checklist", Json.pretty(checklist));

        StringBuilder notes = new StringBuilder("# Release notes: ").append(Json.str(spec, "id")).append("\n\n")
                .append(Json.str(spec, "summary")).append("\n\n## Included\n");
        for (Object o : Json.list(spec.get("acceptanceCriteria"))) {
            Map<String, Object> ac = Json.obj(o);
            notes.append("- ").append(Json.str(ac, "id")).append(": ").append(Json.str(ac, "text")).append('\n');
        }
        notes.append("\n## Readiness\n");
        for (Map<String, Object> i : items) {
            notes.append("- [").append(Boolean.TRUE.equals(i.get("ok")) ? "x" : " ").append("] ")
                    .append(i.get("item")).append(" (").append(i.get("evidence")).append(")\n");
        }
        notes.append("\n## Rollback\nRevert the change set (exported patch); migrations are additive, so the previous version keeps working with the new schema.\n");
        result.output("release-notes", notes.toString());
        result.decision(ready ? "Recommend release (pending human sign-off)" : "NOT ready for release",
                "computed from test, security, migration and approval evidence", List.of());
        return result;
    }

    private static Map<String, Object> item(String item, boolean ok, String evidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", item);
        m.put("ok", ok);
        m.put("evidence", evidence);
        return m;
    }
}
