package com.example.sdlc.agents;

import com.example.sdlc.json.Json;
import com.example.sdlc.model.Risk;
import com.example.sdlc.model.StageDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task decomposition: acceptance criteria → tasks with dependencies → mapped to stages. Also <b>dynamic
 * re-planning</b>: when the design introduces a schema migration, the planner adds a {@code migration-check} stage
 * to the live graph (after implement, before verify). The engine validates the new graph before accepting it.
 */
public final class PlannerAgent implements Agent {

    @Override
    public String name() {
        return "planner";
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        AgentResult result = new AgentResult();
        Map<String, Object> spec = ctx.inputJson("spec");
        Map<String, Object> impact = ctx.inputJson("impact");
        List<Map<String, Object>> tasks = new ArrayList<>();
        int n = 1;
        List<String> allAcs = new ArrayList<>();
        Map<String, List<String>> acsByFeature = new LinkedHashMap<>();
        for (Object o : Json.list(spec.get("acceptanceCriteria"))) {
            Map<String, Object> ac = Json.obj(o);
            allAcs.add(Json.str(ac, "id"));
            acsByFeature.computeIfAbsent(Json.str(ac, "feature"), k -> new ArrayList<>()).add(Json.str(ac, "id"));
        }
        boolean migration = !Json.list(impact.get("newMigrations")).isEmpty();
        String migrationTask = null;
        if (migration) {
            migrationTask = "T" + n++;
            tasks.add(task(migrationTask, "Write additive Flyway migration " + Json.list(impact.get("newMigrations")),
                    "implement", List.of(), List.of()));
        }
        List<String> implTasks = new ArrayList<>();
        for (Object o : Json.list(impact.get("impacted"))) {
            Map<String, Object> i = Json.obj(o);
            String feature = Json.str(i, "feature");
            List<String> acs = acsByFeature.getOrDefault(feature, List.of());
            boolean present = Json.str(i, "status", "").startsWith("already");
            String impl = "T" + n++;
            tasks.add(task(impl, (present ? "Verify existing " : "Implement ") + feature, "implement", acs,
                    migrationTask == null || present ? List.of() : List.of(migrationTask)));
            implTasks.add(impl);
            tasks.add(task("T" + n++, "Tests proving " + acs, "test-design", acs, List.of()));
        }
        tasks.add(task("T" + n++, "Run full test suite and check AC traceability", "verify", allAcs, implTasks));
        tasks.add(task("T" + n++, "Security & compliance review of the change set", "security", List.of(), List.of()));
        tasks.add(task("T" + n++, "Docs: change note + CHANGELOG", "docs", allAcs, List.of()));
        tasks.add(task("T" + n, "Release readiness checklist + sign-off", "release", allAcs, List.of()));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("specId", Json.str(spec, "id"));
        plan.put("tasks", tasks);
        plan.put("parallelism", "implement ‖ test-design, then security ‖ docs");
        result.output("plan", Json.pretty(plan));

        if (migration) {
            StageDef s = new StageDef();
            s.id = "migration-check";
            s.agent = "migration-check";
            s.dependsOn = new ArrayList<>(List.of("implement"));
            s.consumes = new ArrayList<>(List.of("code-changes", "impact"));
            s.produces = new ArrayList<>(List.of("migration-check"));
            s.exitGates = new ArrayList<>(List.of("required-outputs", "migration-safe"));
            s.risk = Risk.MEDIUM;
            s.maxAttempts = 1;
            s.addedBy = "planner";
            s.reason = "design introduces a schema migration " + Json.list(impact.get("newMigrations"));
            result.proposedStages.add(s);
            result.addDependencies.put("verify", List.of("migration-check"));
            result.decision("Insert migration-check stage into the live graph",
                    "schema changes are the hardest changes to roll back; an automated additive-only check runs before tests",
                    List.of("rely on human approval only", "static workflow with an always-on migration stage"));
        }
        result.decision("Decomposed into " + tasks.size() + " tasks",
                "every acceptance criterion maps to at least one implementation and one test task", List.of());
        return result;
    }

    private static Map<String, Object> task(String id, String title, String stage, List<String> acs, List<String> deps) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("title", title);
        t.put("stage", stage);
        t.put("acceptanceCriteria", acs);
        t.put("dependsOn", deps);
        return t;
    }
}
