package com.example.sdlc.analysis;

import com.example.sdlc.workspace.GuardedWorkspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brownfield codebase reasoning from evidence in the code. Builds a map of packages, Spring endpoints, JPA entities,
 * Flyway migrations and tests, then works out which modules a change touches.
 *
 * <p>Known limitation: this uses regex/structural heuristics, not a full parser. That is enough for a Spring codebase
 * with conventional structure; a production version would use JavaParser or an LSP index.
 */
public final class RepoAnalyzer {

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern TYPE = Pattern.compile("(?:public\\s+)?(?:final\\s+)?(class|record|interface|enum)\\s+(\\w+)");
    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\"([^\"]*)\"\\)");
    private static final Pattern METHOD_MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping(?:\\(\"([^\"]*)\"\\))?");
    private static final Pattern TABLE = Pattern.compile("@Table\\(name\\s*=\\s*\"(\\w+)\"\\)");

    private final GuardedWorkspace ws;

    public RepoAnalyzer(GuardedWorkspace ws) {
        this.ws = ws;
    }

    public Map<String, Object> map() {
        List<Map<String, Object>> classes = new ArrayList<>();
        List<String> endpoints = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        List<String> migrations = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        for (String path : ws.listFiles()) {
            if (path.contains("db/migration/") && path.endsWith(".sql")) {
                migrations.add(path.substring(path.lastIndexOf('/') + 1));
            }
            if (!path.endsWith(".java")) {
                continue;
            }
            String src = ws.read(path);
            if (path.startsWith("src/test/")) {
                Matcher t = TYPE.matcher(src);
                if (t.find()) {
                    tests.add(t.group(2));
                }
                continue;
            }
            Matcher pk = PACKAGE.matcher(src);
            Matcher type = TYPE.matcher(src);
            String pkg = pk.find() ? pk.group(1) : "";
            String name = type.find() ? type.group(2) : path;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("path", path);
            c.put("package", pkg);
            c.put("type", name);
            classes.add(c);
            Matcher base = CLASS_MAPPING.matcher(src);
            String prefix = base.find() ? base.group(1) : "";
            Matcher mm = METHOD_MAPPING.matcher(src);
            while (mm.find()) {
                endpoints.add(mm.group(1).toUpperCase(Locale.ROOT) + " " + prefix + (mm.group(2) == null ? "" : mm.group(2))
                        + "  (" + name + ")");
            }
            if (src.contains("@Entity")) {
                Matcher tb = TABLE.matcher(src);
                entities.add(name + (tb.find() ? " → " + tb.group(1) : ""));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("classes", classes.size());
        m.put("endpoints", endpoints);
        m.put("entities", entities);
        m.put("migrations", migrations);
        m.put("testClasses", tests.size());
        m.put("classIndex", classes);
        return m;
    }

    /** Files whose package path matches one of the component names (e.g. "service", "db/migration"). */
    public List<String> filesFor(List<String> components) {
        List<String> out = new ArrayList<>();
        for (String path : ws.listFiles()) {
            if (!path.startsWith("src/main/")) {
                continue;
            }
            for (String c : components) {
                if (path.contains("/" + c + "/")) {
                    out.add(path);
                    break;
                }
            }
        }
        return out;
    }

    public int nextMigrationVersion() {
        int max = 0;
        for (String path : ws.listFiles()) {
            Matcher m = Pattern.compile("db/migration/V(\\d+)__").matcher(path);
            if (m.find()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return max + 1;
    }
}
