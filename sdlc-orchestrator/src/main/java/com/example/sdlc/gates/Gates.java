package com.example.sdlc.gates;

import com.example.sdlc.json.Json;
import com.example.sdlc.model.StageDef;
import com.example.sdlc.policy.Effect;
import com.example.sdlc.policy.PolicyEngine;
import com.example.sdlc.policy.PolicyFinding;
import com.example.sdlc.tools.TestRunner;
import com.example.sdlc.workspace.GuardedWorkspace;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry and exit gates. A gate is a deterministic, explainable check: no model is involved. Gates are where
 * "the agent says it's done" becomes "the evidence says it's done".
 */
public final class Gates {

    public record GateResult(String gate, boolean passed, String message, List<String> details) {

        static GateResult pass(String gate, String message) {
            return new GateResult(gate, true, message, List.of());
        }

        static GateResult fail(String gate, String message, List<String> details) {
            return new GateResult(gate, false, message, details);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gate", gate);
            m.put("passed", passed);
            m.put("message", message);
            m.put("details", details);
            return m;
        }

        public String feedback() {
            return "gate " + gate + " failed: " + message + (details.isEmpty() ? "" : " → " + String.join("; ", details));
        }
    }

    /** Snapshot a gate evaluates: the stage, its inputs, the agent's outputs, the workspace and the config. */
    public record GateContext(StageDef stage, Map<String, String> inputs, Map<String, String> outputs,
                              GuardedWorkspace workspace, Map<String, Object> config, TestRunner testRunner) {

        String artifact(String name) {
            String v = outputs.get(name);
            return v != null ? v : inputs.get(name);
        }
    }

    @FunctionalInterface
    public interface Gate {
        GateResult check(GateContext ctx) throws Exception;
    }

    private final Map<String, Gate> gates = new LinkedHashMap<>();

    public Gates(PolicyEngine policy) {
        gates.put("required-outputs", Gates::requiredOutputs);
        gates.put("spec-ready", ctx -> specReady("spec-ready", ctx.artifact("spec")));
        gates.put("spec-complete", ctx -> specReady("spec-complete", ctx.artifact("spec")));
        gates.put("compliance-text", ctx -> compliance(policy, ctx));
        gates.put("design-covers-acs", Gates::designCoversAcs);
        gates.put("plan-valid", Gates::planValid);
        gates.put("build", Gates::build);
        gates.put("tests-cover-acs", Gates::testsCoverAcs);
        gates.put("tests-pass", Gates::testsPass);
        gates.put("ac-traceability", Gates::acTraceability);
        gates.put("migration-safe", ctx -> Json.bool(Json.parseObject(ctx.artifact("migration-check")), "ok", false)
                ? GateResult.pass("migration-safe", "migration checks passed")
                : GateResult.fail("migration-safe", "migration is not additive/backward compatible", failedChecks(ctx.artifact("migration-check"))));
        gates.put("no-high-findings", Gates::noHighFindings);
        gates.put("release-checklist", Gates::releaseChecklist);
    }

    public Set<String> names() {
        return gates.keySet();
    }

    public GateResult run(String name, GateContext ctx) {
        Gate g = gates.get(name);
        if (g == null) {
            return GateResult.fail(name, "unknown gate", List.of());
        }
        try {
            return g.check(ctx);
        } catch (Exception e) {
            return GateResult.fail(name, "gate error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), List.of());
        }
    }

    // ---------------------------------------------------------------- gate implementations

    private static GateResult requiredOutputs(GateContext ctx) {
        List<String> missing = new ArrayList<>();
        for (String p : ctx.stage().produces) {
            String v = ctx.outputs().get(p);
            if (v == null || v.isBlank()) {
                missing.add(p);
            }
        }
        return missing.isEmpty() ? GateResult.pass("required-outputs", "all declared outputs present")
                : GateResult.fail("required-outputs", "missing outputs", missing);
    }

    private static GateResult specReady(String gate, String specJson) {
        if (specJson == null) {
            return GateResult.fail(gate, "no spec", List.of());
        }
        Map<String, Object> spec = Json.parseObject(specJson);
        List<String> problems = new ArrayList<>();
        if (!"READY".equals(spec.get("status"))) {
            problems.add("status is " + spec.get("status"));
        }
        if (!Json.list(spec.get("openQuestions")).isEmpty()) {
            problems.add("open questions remain");
        }
        if (Json.list(spec.get("acceptanceCriteria")).isEmpty()) {
            problems.add("no acceptance criteria");
        }
        return problems.isEmpty() ? GateResult.pass(gate, "spec is READY with " + Json.list(spec.get("acceptanceCriteria")).size() + " ACs")
                : GateResult.fail(gate, "spec not ready", problems);
    }

    private static GateResult compliance(PolicyEngine policy, GateContext ctx) {
        List<PolicyFinding> findings = policy.evaluateText("spec", ctx.artifact("spec"));
        List<String> denies = findings.stream().filter(f -> f.effect() == Effect.DENY).map(PolicyFinding::summary).toList();
        return denies.isEmpty() ? GateResult.pass("compliance-text", "no compliance violations in requirement")
                : GateResult.fail("compliance-text", "requirement violates compliance policy", denies);
    }

    private static GateResult designCoversAcs(GateContext ctx) {
        String design = ctx.artifact("design");
        List<String> missing = new ArrayList<>();
        for (String ac : acIds(ctx.artifact("spec"))) {
            if (design == null || !design.contains(ac)) {
                missing.add(ac);
            }
        }
        return missing.isEmpty() ? GateResult.pass("design-covers-acs", "design references every AC")
                : GateResult.fail("design-covers-acs", "design does not cover all ACs", missing);
    }

    private static GateResult planValid(GateContext ctx) {
        Map<String, Object> plan = Json.parseObject(ctx.artifact("plan"));
        Set<String> ids = new HashSet<>();
        Set<String> covered = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (Object o : Json.list(plan.get("tasks"))) {
            Map<String, Object> t = Json.obj(o);
            ids.add(Json.str(t, "id"));
            covered.addAll(Json.strings(t.get("acceptanceCriteria")));
        }
        for (Object o : Json.list(plan.get("tasks"))) {
            Map<String, Object> t = Json.obj(o);
            for (String d : Json.strings(t.get("dependsOn"))) {
                if (!ids.contains(d)) {
                    problems.add(Json.str(t, "id") + " depends on unknown task " + d);
                }
            }
        }
        for (String ac : acIds(ctx.artifact("spec"))) {
            if (!covered.contains(ac)) {
                problems.add(ac + " has no task");
            }
        }
        return problems.isEmpty() ? GateResult.pass("plan-valid", ids.size() + " tasks cover all ACs")
                : GateResult.fail("plan-valid", "plan is incomplete", problems);
    }

    private static GateResult build(GateContext ctx) throws Exception {
        Object cmd = ctx.config().get("buildCommand");
        if (cmd == null || cmd.toString().isBlank()) {
            return GateResult.pass("build", "skipped (no buildCommand configured; compilation is covered by verify)");
        }
        TestRunner.Result r = ctx.testRunner().run(ctx.workspace().root(), cmd.toString(), Duration.ofMinutes(10));
        return r.exitCode() == 0 ? GateResult.pass("build", "build succeeded in " + r.durationMs() + " ms")
                : GateResult.fail("build", "build failed (exit " + r.exitCode() + ")", List.of(tail(r.outputTail())));
    }

    private static GateResult testsCoverAcs(GateContext ctx) {
        Map<String, Object> cases = Json.parseObject(ctx.artifact("test-cases"));
        List<String> uncovered = new ArrayList<>();
        for (Object o : Json.list(cases.get("cases"))) {
            Map<String, Object> c = Json.obj(o);
            if (Json.list(c.get("tests")).isEmpty()) {
                uncovered.add(Json.str(c, "ac"));
            }
        }
        return uncovered.isEmpty() ? GateResult.pass("tests-cover-acs", "every AC has at least one test")
                : GateResult.fail("tests-cover-acs", "ACs without tests", uncovered);
    }

    private static GateResult testsPass(GateContext ctx) {
        Map<String, Object> r = Json.parseObject(ctx.artifact("test-report"));
        long tests = Json.num(r, "tests", 0);
        long failing = Json.num(r, "failures", 0) + Json.num(r, "errors", 0);
        long exit = Json.num(r, "exitCode", -1);
        if (Json.bool(r, "timedOut", false)) {
            return GateResult.fail("tests-pass", "test run timed out", List.of());
        }
        if (tests == 0) {
            return GateResult.fail("tests-pass", "no tests were executed (exit " + exit + ")", List.of(tail(Json.str(r, "outputTail", ""))));
        }
        if (failing > 0 || exit != 0) {
            List<String> details = new ArrayList<>(Json.strings(r.get("failed")));
            if (details.isEmpty()) {
                details.add(tail(Json.str(r, "outputTail", "")));
            }
            return GateResult.fail("tests-pass", failing + " of " + tests + " tests failing", details);
        }
        return GateResult.pass("tests-pass", tests + " tests passed");
    }

    private static GateResult acTraceability(GateContext ctx) {
        Map<String, Object> r = Json.parseObject(ctx.artifact("test-report"));
        Set<String> passed = new HashSet<>(Json.strings(r.get("passed")));
        List<String> missing = new ArrayList<>();
        for (Object o : Json.list(Json.parseObject(ctx.artifact("spec")).get("acceptanceCriteria"))) {
            Map<String, Object> ac = Json.obj(o);
            for (String t : Json.strings(ac.get("verifiedBy"))) {
                if (!passed.contains(t)) {
                    missing.add(Json.str(ac, "id") + " needs " + t);
                }
            }
        }
        return missing.isEmpty() ? GateResult.pass("ac-traceability", "every AC is proven by a passing test")
                : GateResult.fail("ac-traceability", "acceptance criteria not proven by passing tests", missing);
    }

    private static GateResult noHighFindings(GateContext ctx) {
        Map<String, Object> s = Json.parseObject(ctx.artifact("security-findings"));
        List<String> blocking = new ArrayList<>();
        for (Object o : Json.list(s.get("findings"))) {
            Map<String, Object> f = Json.obj(o);
            if (List.of("HIGH", "CRITICAL").contains(Json.str(f, "severity"))) {
                blocking.add(Json.str(f, "rule") + " " + Json.str(f, "path") + ": " + Json.str(f, "message"));
            }
        }
        return blocking.isEmpty() ? GateResult.pass("no-high-findings", "no blocking security findings")
                : GateResult.fail("no-high-findings", "blocking security findings", blocking);
    }

    private static GateResult releaseChecklist(GateContext ctx) {
        Map<String, Object> c = Json.parseObject(ctx.artifact("release-checklist"));
        List<String> open = new ArrayList<>();
        for (Object o : Json.list(c.get("items"))) {
            Map<String, Object> i = Json.obj(o);
            if (!Boolean.TRUE.equals(i.get("ok"))) {
                open.add(Json.str(i, "item") + " (" + Json.str(i, "evidence") + ")");
            }
        }
        return open.isEmpty() ? GateResult.pass("release-checklist", "all readiness items satisfied")
                : GateResult.fail("release-checklist", "release checklist incomplete", open);
    }

    private static List<String> acIds(String specJson) {
        List<String> ids = new ArrayList<>();
        if (specJson == null) {
            return ids;
        }
        for (Object o : Json.list(Json.parseObject(specJson).get("acceptanceCriteria"))) {
            ids.add(Json.str(Json.obj(o), "id"));
        }
        return ids;
    }

    private static List<String> failedChecks(String json) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.list(Json.parseObject(json).get("checks"))) {
            Map<String, Object> c = Json.obj(o);
            if (!Boolean.TRUE.equals(c.get("ok"))) {
                out.add(Json.str(c, "check") + ": " + Json.str(c, "detail"));
            }
        }
        return out;
    }

    private static String tail(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() > 600 ? "…" + t.substring(t.length() - 600) : t;
    }
}
