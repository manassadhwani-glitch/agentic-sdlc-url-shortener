package com.example.sdlc.policy;

import com.example.sdlc.model.FileChange;
import com.example.sdlc.workspace.BoundaryViolation;
import com.example.sdlc.workspace.GuardedWorkspace;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Policy-as-code guardrails in three groups: security, compliance and change control.
 * Each rule returns ALLOW / REQUIRE_APPROVAL / DENY with a reason and a remediation hint.
 *
 * <p>Evaluated on every proposed change set <i>before</i> it touches the workspace, and on requirement / spec
 * text (compliance). The rules are deliberately conservative and pattern-based. Known limitation: pattern
 * matching can be evaded; production would add SAST and secret scanning such as gitleaks or Semgrep.
 */
public final class PolicyEngine {

    public static final String ROLE_TECH_LEAD = "tech-lead";
    public static final String ROLE_SECURITY = "security";

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("sk-(ant-)?[A-Za-z0-9_-]{20,}"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{30,}"),
            Pattern.compile("(?i)(secret|password|passwd|api[_-]?key|token)\\s*[:=]\\s*[\"'][A-Za-z0-9/+_=-]{20,}[\"']"));

    private static final List<Pattern> FORBIDDEN_JAVA = List.of(
            Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec"),
            Pattern.compile("new\\s+ProcessBuilder\\s*\\("),
            Pattern.compile("create(Native)?Query\\(\\s*\"[^\"]*\"\\s*\\+"),
            Pattern.compile("\\.execute(Query|Update)?\\(\\s*\"[^\"]*\"\\s*\\+"),
            Pattern.compile("(?i)trustall|setHostnameVerifier\\(\\s*\\(")
    );

    private static final Pattern DESTRUCTIVE_SQL = Pattern.compile("(?i)\\b(drop\\s+(table|column)|truncate\\s+table|alter\\s+table\\s+\\w+\\s+drop)\\b");
    private static final Pattern GROUP_ID = Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern PII_STORAGE_TEXT = Pattern.compile(
            "(?i)\\b(store|log|persist|save|record|keep|track|capture|collect)\\w*\\s+(?:the\\s+|their\\s+|each\\s+)?"
                    + "(?:raw\\s+|full\\s+|exact\\s+|plain(?:text)?\\s+|visitors?'?s?\\s+|users?'?s?\\s+|client\\s+)*"
                    + "(ip(?:\\s+address(?:es)?)?s?|e-?mail(?:\\s+address(?:es)?)?s?|precise\\s+location|gps\\s+coordinates)\\b");
    private static final Pattern PII_LOGGING_CODE = Pattern.compile("(?i)log\\.(info|debug|warn|error)\\([^;]*(email|password|ssn|getRemoteAddr)");

    private final Set<String> dependencyAllowlist;
    private final int maxChangedLines;

    public PolicyEngine(Set<String> dependencyAllowlist, int maxChangedLines) {
        this.dependencyAllowlist = dependencyAllowlist;
        this.maxChangedLines = maxChangedLines;
    }

    public static PolicyEngine defaults() {
        return new PolicyEngine(Set.of("org.springframework.boot", "org.springframework", "com.github.ben-manes.caffeine",
                "io.micrometer", "org.flywaydb", "com.h2database", "org.postgresql", "org.junit.jupiter", "org.mockito",
                "org.assertj", "org.jacoco", "com.example", "jakarta.validation"), 400);
    }

    /** Reviews a proposed change set for a stage. */
    public List<PolicyFinding> evaluateChanges(List<FileChange> changes, List<String> writeScope, GuardedWorkspace ws) {
        List<PolicyFinding> out = new ArrayList<>();
        int totalLines = 0;
        for (FileChange c : changes) {
            String path = c.path == null ? "" : c.path.replace('\\', '/');
            String text = c.introducedText();
            totalLines += c.addedLines();

            // --- security
            try {
                ws.checkWritable(path, writeScope);
            } catch (BoundaryViolation e) {
                out.add(new PolicyFinding("SEC-004", "security", Effect.DENY, true, null, path, e.getMessage(),
                        "agents may only write inside their stage's write scope"));
                continue;
            }
            for (Pattern p : SECRET_PATTERNS) {
                if (p.matcher(text).find()) {
                    out.add(new PolicyFinding("SEC-001", "security", Effect.DENY, true, null, path,
                            "possible hard-coded secret", "read secrets from the environment or a vault, never commit them"));
                    break;
                }
            }
            if (path.endsWith(".java")) {
                for (Pattern p : FORBIDDEN_JAVA) {
                    Matcher m = p.matcher(text);
                    if (m.find()) {
                        out.add(new PolicyFinding("SEC-002", "security", Effect.DENY, false, null, path,
                                "forbidden API or pattern: " + m.group(),
                                "use parameterised queries and avoid process execution / TLS bypass"));
                    }
                }
                if (PII_LOGGING_CODE.matcher(text).find()) {
                    out.add(new PolicyFinding("CMP-002", "compliance", Effect.DENY, false, null, path,
                            "personal data written to logs", "log identifiers or hashes, never raw personal data"));
                }
            }

            // --- change control
            if (matches("**/db/migration/V*__*.sql", path)) {
                if (c.op != FileChange.Op.CREATE) {
                    out.add(new PolicyFinding("CHG-001", "change-control", Effect.DENY, false, null, path,
                            "existing migrations are immutable", "add a new, higher-versioned migration instead"));
                } else {
                    out.add(new PolicyFinding("CHG-001", "change-control", Effect.REQUIRE_APPROVAL, false, ROLE_TECH_LEAD,
                            path, "database schema change", null));
                }
                if (DESTRUCTIVE_SQL.matcher(text).find()) {
                    out.add(new PolicyFinding("CHG-006", "change-control", Effect.DENY, false, null, path,
                            "destructive DDL in migration", "use expand/contract: add new columns, migrate, drop later in a separate release"));
                }
            }
            if (path.endsWith("pom.xml") || path.endsWith("build.gradle")) {
                Matcher m = GROUP_ID.matcher(text);
                Set<String> bad = new LinkedHashSet<>();
                while (m.find()) {
                    String g = m.group(1).trim();
                    if (dependencyAllowlist.stream().noneMatch(a -> g.equals(a) || g.startsWith(a + "."))) {
                        bad.add(g);
                    }
                }
                if (!bad.isEmpty()) {
                    out.add(new PolicyFinding("CHG-002", "change-control", Effect.DENY, false, null, path,
                            "dependency not on allow-list: " + bad, "request an allow-list review"));
                } else if (c.op != FileChange.Op.CREATE || ws.exists(path)) {
                    out.add(new PolicyFinding("CHG-002", "change-control", Effect.REQUIRE_APPROVAL, false, ROLE_TECH_LEAD,
                            path, "build / dependency change", null));
                }
            }
            if (matches("**/security/**", path) || matches("**/application*.yml", path) || matches("**/application*.properties", path)) {
                if (ws.exists(path)) {
                    out.add(new PolicyFinding("CHG-003", "change-control", Effect.REQUIRE_APPROVAL, false, ROLE_SECURITY,
                            path, "change to security code or runtime configuration", null));
                }
            }
            if (c.op == FileChange.Op.DELETE) {
                out.add(new PolicyFinding("CHG-005", "change-control", Effect.REQUIRE_APPROVAL, false, ROLE_TECH_LEAD,
                        path, "file deletion", null));
            }
        }
        if (totalLines > maxChangedLines) {
            out.add(new PolicyFinding("CHG-004", "change-control", Effect.REQUIRE_APPROVAL, false, ROLE_TECH_LEAD, null,
                    "large change: " + totalLines + " lines (limit " + maxChangedLines + ")", null));
        }
        return out;
    }

    /** Compliance review of requirement / specification text. */
    public List<PolicyFinding> evaluateText(String source, String text) {
        List<PolicyFinding> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\R")) {
            Matcher m = PII_STORAGE_TEXT.matcher(line);
            if (m.find() && !line.toLowerCase(Locale.ROOT).matches(".*\\b(hash\\w*|hmac|pseudonym\\w*|anonymi[sz]\\w*|never|not|no)\\b.*")) {
                out.add(new PolicyFinding("CMP-001", "compliance", Effect.DENY, false, null, source,
                        "requirement stores personal data in identifiable form: \"" + m.group().trim() + "\"",
                        "store only a salted HMAC (pseudonymised) and aggregate counts; never raw IP / e-mail / location"));
            }
        }
        return out;
    }

    public static Effect strictest(List<PolicyFinding> findings) {
        Effect e = Effect.ALLOW;
        for (PolicyFinding f : findings) {
            if (f.effect().ordinal() > e.ordinal()) {
                e = f.effect();
            }
        }
        return e;
    }

    public static boolean hasCritical(List<PolicyFinding> findings) {
        return findings.stream().anyMatch(f -> f.critical() && f.effect() == Effect.DENY);
    }

    /** Distinct roles that must approve, in a stable order (security first). */
    public static List<String> requiredRoles(List<PolicyFinding> findings) {
        Set<String> roles = new LinkedHashSet<>();
        findings.stream().filter(f -> f.effect() == Effect.REQUIRE_APPROVAL && ROLE_SECURITY.equals(f.requiredRole()))
                .forEach(f -> roles.add(ROLE_SECURITY));
        findings.stream().filter(f -> f.effect() == Effect.REQUIRE_APPROVAL && f.requiredRole() != null)
                .forEach(f -> roles.add(f.requiredRole()));
        return new ArrayList<>(roles);
    }

    private static boolean matches(String glob, String path) {
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        Path p = Path.of(path);
        return m.matches(p) || (glob.startsWith("**/") && FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)).matches(p));
    }
}
