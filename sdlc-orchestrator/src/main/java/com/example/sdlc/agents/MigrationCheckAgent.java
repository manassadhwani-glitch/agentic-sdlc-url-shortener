package com.example.sdlc.agents;

import com.example.sdlc.json.Json;
import com.example.sdlc.model.FileChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automated schema-change review, inserted into the graph by the planner when a migration is needed.
 * Checks: versions are contiguous, the new file is additive only, and added columns are nullable or have a default
 * (so old application versions keep working, which is what makes rollback safe).
 */
public final class MigrationCheckAgent implements Agent {

    private static final Pattern VERSION = Pattern.compile("db/migration/V(\\d+)__\\w+\\.sql$");
    private static final Pattern DESTRUCTIVE = Pattern.compile("(?i)\\b(drop|rename|truncate)\\b");
    private static final Pattern NOT_NULL_NO_DEFAULT = Pattern.compile("(?i)add\\s+column[^;]*not\\s+null(?![^;]*default)");

    @Override
    public String name() {
        return "migration-check";
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        AgentResult result = new AgentResult();
        List<Map<String, Object>> checks = new ArrayList<>();
        TreeMap<Integer, String> versions = new TreeMap<>();
        for (String p : ctx.workspace().listFiles()) {
            Matcher m = VERSION.matcher(p);
            if (m.find()) {
                versions.put(Integer.parseInt(m.group(1)), p);
            }
        }
        int expected = 1;
        boolean contiguous = true;
        for (int v : versions.keySet()) {
            if (v != expected++) {
                contiguous = false;
            }
        }
        checks.add(check("versions-contiguous", contiguous, "versions " + versions.keySet()));

        List<FileChange> changes = FileChange.listFromJson(Json.parseObject(ctx.input("code-changes")).get("changes"));
        for (FileChange c : changes) {
            if (c.path == null || !VERSION.matcher(c.path).find()) {
                continue;
            }
            String sql = c.introducedText();
            checks.add(check("new-file-only:" + c.path, c.op == FileChange.Op.CREATE, "op " + c.op));
            checks.add(check("additive:" + c.path, !DESTRUCTIVE.matcher(sql).find(), "no DROP/RENAME/TRUNCATE"));
            checks.add(check("backward-compatible:" + c.path, !NOT_NULL_NO_DEFAULT.matcher(sql).find(),
                    "added columns nullable or defaulted"));
            checks.add(check("has-rollback-note:" + c.path, sql.toLowerCase(Locale.ROOT).contains("rollback"),
                    "documents how to roll back"));
        }
        boolean ok = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("checks", checks);
        result.output("migration-check", Json.pretty(out));
        result.decision("Migration review: " + (ok ? "safe" : "UNSAFE"), "automated expand/contract checks", List.of());
        return result;
    }

    private static Map<String, Object> check(String name, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", name);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }
}
