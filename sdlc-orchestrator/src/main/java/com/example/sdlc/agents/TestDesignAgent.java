package com.example.sdlc.agents;

import com.example.sdlc.json.Json;
import com.example.sdlc.model.FileChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives test cases from acceptance criteria (not from the implementation). It runs in parallel with
 * implementation, so tests are written against intent, and a buggy implementation can't shape its own tests.
 */
public final class TestDesignAgent implements Agent {

    @Override
    public String name() {
        return "test-design";
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        AgentResult result = new AgentResult();
        Map<String, Object> spec = ctx.inputJson("spec");
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Object o : Json.list(spec.get("acceptanceCriteria"))) {
            Map<String, Object> ac = Json.obj(o);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("ac", Json.str(ac, "id"));
            c.put("criterion", Json.str(ac, "text"));
            c.put("tests", ac.get("verifiedBy"));
            c.put("level", Json.strings(ac.get("verifiedBy")).stream().anyMatch(t -> t.contains("Integration")) ? "integration" : "unit");
            cases.add(c);
        }
        result.output("test-cases", Json.pretty(Map.of("specId", Json.str(spec, "id"), "cases", cases)));

        // Decide from the TEST tree only. Implementation runs in parallel and may be editing src/main right now,
        // so "is the feature present?" would be a race. "Do its test files exist?" is not.
        Set<String> playbooks = new LinkedHashSet<>();
        boolean reference = false;
        for (String id : Json.strings(spec.get("features"))) {
            Catalog.Feature f = ctx.catalog().feature(id);
            if (f.testPlaybook() == null) {
                continue;
            }
            if ("reference".equals(f.testPlaybook())) {
                reference = true;
            } else {
                playbooks.add(f.testPlaybook());
            }
        }
        if (reference) {
            result.changes.addAll(Playbooks.fromReference(ctx.referenceDir(), p -> p.startsWith("src/test/"), ctx));
        }
        for (String p : playbooks) {
            for (FileChange c : Playbooks.load(p)) {
                if (c.op != FileChange.Op.CREATE || !ctx.workspace().exists(c.path)) {
                    result.changes.add(c);
                }
            }
        }
        if (ctx.fallbackMode()) {
            result.mode = "fallback";
        }
        result.decision("Designed " + cases.size() + " AC-to-test mappings, " + result.changes.size() + " test files",
                "tests come from acceptance criteria, written in parallel with (and independent of) the implementation",
                List.of("generate tests from the implementation (risks confirming bugs)"));
        return result;
    }
}
