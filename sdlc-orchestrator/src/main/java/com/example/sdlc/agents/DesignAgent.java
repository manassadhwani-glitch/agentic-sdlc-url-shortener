package com.example.sdlc.agents;

import com.example.sdlc.analysis.RepoAnalyzer;
import com.example.sdlc.json.Json;
import com.example.sdlc.llm.LlmClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Architecture/design plus brownfield impact analysis. Reads the actual workspace (endpoints, entities,
 * migrations) to say which modules, APIs and data flows a change touches before any code is written.
 * The design must reference every acceptance criterion (checked by the design-covers-acs gate).
 */
public final class DesignAgent implements Agent {

    @Override
    public String name() {
        return "design";
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        AgentResult result = new AgentResult();
        Map<String, Object> spec = ctx.inputJson("spec");
        List<Object> criteria = Json.list(spec.get("acceptanceCriteria"));
        RepoAnalyzer analyzer = ctx.analyzer();
        Map<String, Object> repo = analyzer.map();
        boolean greenfield = ((Number) repo.get("classes")).intValue() == 0;

        List<Map<String, Object>> impacted = new ArrayList<>();
        List<String> newMigrations = new ArrayList<>();
        int nextMigration = analyzer.nextMigrationVersion();
        StringBuilder coverage = new StringBuilder("| AC | Feature | Design element |\n|---|---|---|\n");
        for (String fid : Json.strings(spec.get("features"))) {
            Catalog.Feature f = ctx.catalog().feature(fid);
            boolean present = f.presentIn(ctx.workspace());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("feature", fid);
            entry.put("status", present ? "already implemented (verify only)" : greenfield ? "new" : "change");
            entry.put("components", f.components());
            entry.put("files", present || greenfield ? List.of() : analyzer.filesFor(f.components()));
            impacted.add(entry);
            if (f.needsMigration() && !present) {
                newMigrations.add("V" + nextMigration++ + " (additive, for " + fid + ")");
            }
        }
        for (Object o : criteria) {
            Map<String, Object> ac = Json.obj(o);
            Catalog.Feature f = ctx.catalog().feature(Json.str(ac, "feature"));
            coverage.append("| ").append(Json.str(ac, "id")).append(" | ").append(f.title()).append(" | ")
                    .append(String.join(", ", f.components())).append(" |\n");
        }

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("mode", greenfield ? "greenfield" : "brownfield");
        impact.put("repository", Map.of("classes", repo.get("classes"), "endpoints", repo.get("endpoints"),
                "entities", repo.get("entities"), "migrations", repo.get("migrations"), "testClasses", repo.get("testClasses")));
        impact.put("impacted", impacted);
        impact.put("newMigrations", newMigrations);
        impact.put("risks", risks(newMigrations, greenfield));
        result.output("impact", Json.pretty(impact));

        String design = null;
        if (ctx.useLlm()) {
            result.llmCalls++;
            design = llmDesign(ctx, spec, impact).orElse(null);
            result.mode = design != null ? "llm" : "fallback";
        }
        if (design == null) {
            design = deterministicDesign(spec, impact, coverage.toString(), greenfield, ctx.feedback());
        }
        result.output("design", design);
        result.decision(greenfield ? "Greenfield layered Spring Boot service" : "Extend existing modules in place",
                greenfield ? "standard api → service → domain → repository layering keeps modules testable in isolation"
                        : "impact analysis shows the change fits existing module boundaries; no new service needed",
                List.of("separate microservice per capability (premature for this scope)"));
        if (!newMigrations.isEmpty()) {
            result.decision("Additive, forward-only schema migration " + newMigrations,
                    "nullable new column keeps old rows valid and allows zero-downtime rollout (expand/contract)",
                    List.of("in-place column rewrite (requires downtime)"));
        }
        return result;
    }

    private static List<String> risks(List<String> migrations, boolean greenfield) {
        List<String> r = new ArrayList<>();
        if (!migrations.isEmpty()) {
            r.add("Schema change: must be additive and backward compatible; requires tech-lead approval (CHG-001)");
        }
        if (greenfield) {
            r.add("Large initial change set: requires tech-lead review (CHG-004)");
        }
        r.add("Per-instance cache: disabled/expired state can be stale up to the cache TTL on other replicas");
        return r;
    }

    private static String deterministicDesign(Map<String, Object> spec, Map<String, Object> impact, String coverage,
                                              boolean greenfield, List<String> feedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Design: ").append(Json.str(spec, "id")).append("\n\n");
        sb.append("## Context\n").append(Json.str(spec, "summary")).append("\n\n");
        sb.append("## Approach\n");
        sb.append(greenfield
                ? "New Spring Boot service with layers api → service → domain → repository; Flyway owns the schema; "
                + "analytics are captured asynchronously off the redirect path.\n\n"
                : "Brownfield change inside the existing service. Impacted modules come from static analysis of the workspace "
                + "(see `impact` artifact).\n\n");
        sb.append("## Impacted modules\n");
        for (Object o : Json.list(impact.get("impacted"))) {
            Map<String, Object> i = Json.obj(o);
            sb.append("- **").append(Json.str(i, "feature")).append("**: ").append(Json.str(i, "status"))
                    .append(" (components: ").append(String.join(", ", Json.strings(i.get("components")))).append(")\n");
            for (String f : Json.strings(i.get("files"))) {
                sb.append("  - `").append(f).append("`\n");
            }
        }
        List<String> migrations = Json.strings(impact.get("newMigrations"));
        sb.append("\n## Data model\n").append(migrations.isEmpty() ? "No schema change.\n" : "New migration(s): " + migrations + "\n");
        sb.append("\n## Key decisions (ADR-style)\n");
        sb.append("- **302 not 301**: browsers must not cache the redirect, so clicks are counted and disable/expiry take effect immediately.\n");
        sb.append("- **Database constraints decide races**: application pre-checks only improve error messages.\n");
        sb.append("- **Cache**: read-through, positive entries only, TTL bounds staleness; invalidated locally on change.\n");
        if (!migrations.isEmpty()) {
            sb.append("- **Expand/contract migration**: new nullable column; no destructive DDL.\n");
        }
        if (!feedback.isEmpty()) {
            sb.append("\n## Reviewer feedback addressed\n");
            for (String f : feedback) {
                sb.append("- ").append(f).append('\n');
                String l = f.toLowerCase();
                if (l.contains("cache")) {
                    sb.append("  - Cache invalidation: entries are evicted on disable/expiry change on this node; other nodes converge within the TTL (default 10 min). Production: publish invalidations over Redis pub/sub.\n");
                }
                if (l.contains("rollback") || l.contains("roll back")) {
                    sb.append("  - Rollback: code changes are journaled per stage; schema changes are additive so the previous release keeps working.\n");
                }
                if (l.contains("301") || l.contains("302")) {
                    sb.append("  - 301 vs 302: 301 would be cached by browsers indefinitely, breaking analytics and disable.\n");
                }
            }
        }
        sb.append("\n## Acceptance-criteria coverage\n").append(coverage);
        sb.append("\n## Risks\n");
        for (String r : Json.strings(impact.get("risks"))) {
            sb.append("- ").append(r).append('\n');
        }
        return sb.toString();
    }

    private static Optional<String> llmDesign(AgentContext ctx, Map<String, Object> spec, Map<String, Object> impact) {
        String system = "You are a principal engineer writing a concise design doc (Markdown) for a Spring Boot URL shortener.";
        String user = "Write the design. It MUST mention every acceptance-criterion id (AC-n) in a coverage table, list impacted "
                + "modules, data model changes, ADR-style decisions with alternatives, and risks. Return JSON {\"design\": \"<markdown>\"}.\n"
                + LlmClient.untrusted("spec", Json.pretty(spec)) + "\n" + LlmClient.untrusted("impact", Json.pretty(impact))
                + (ctx.feedback().isEmpty() ? "" : "\nReviewer feedback to address:\n" + String.join("\n", ctx.feedback()));
        return ctx.llm().complete(system, user, 3000).map(Json::extractObject).map(m -> m == null ? null : Json.str(m, "design"))
                .filter(d -> Json.list(spec.get("acceptanceCriteria")).stream()
                        .allMatch(ac -> d.contains(Json.str(Json.obj(ac), "id"))));
    }
}
