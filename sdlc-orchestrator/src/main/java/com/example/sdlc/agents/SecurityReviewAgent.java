package com.example.sdlc.agents;

import com.example.sdlc.json.Json;
import com.example.sdlc.llm.LlmClient;
import com.example.sdlc.model.FileChange;
import com.example.sdlc.policy.Effect;
import com.example.sdlc.policy.PolicyEngine;
import com.example.sdlc.policy.PolicyFinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Independent review of the final change set (code + tests): policy rules again (defence in depth) plus code-smell
 * checks. An optional LLM review adds <i>advisory</i> findings only. A model can never lower a severity or clear a
 * finding.
 */
public final class SecurityReviewAgent implements Agent {

    private static final Map<Pattern, String[]> SMELLS = new LinkedHashMap<>();

    static {
        SMELLS.put(Pattern.compile("printStackTrace\\(\\)"), new String[]{"LOW", "stack traces printed instead of logged"});
        SMELLS.put(Pattern.compile("System\\.out\\.print"), new String[]{"LOW", "System.out in production code"});
        SMELLS.put(Pattern.compile("@CrossOrigin\\(\\s*(origins\\s*=\\s*)?\"\\*\""), new String[]{"MEDIUM", "wildcard CORS"});
        SMELLS.put(Pattern.compile("(?i)md5|sha1\\("), new String[]{"MEDIUM", "weak hash algorithm"});
        SMELLS.put(Pattern.compile("new\\s+Random\\("), new String[]{"LOW", "non-cryptographic RNG (check it isn't used for secrets)"});
    }

    private final PolicyEngine policy;

    public SecurityReviewAgent(PolicyEngine policy) {
        this.policy = policy;
    }

    @Override
    public String name() {
        return "security-review";
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        AgentResult result = new AgentResult();
        List<FileChange> all = new ArrayList<>();
        for (String name : List.of("code-changes", "test-changes")) {
            if (ctx.hasInput(name)) {
                all.addAll(FileChange.listFromJson(Json.parseObject(ctx.input(name)).get("changes")));
            }
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        for (PolicyFinding f : policy.evaluateChanges(all, List.of("**"), ctx.workspace())) {
            if (f.effect() == Effect.DENY) {
                findings.add(finding(f.critical() ? "CRITICAL" : "HIGH", f.ruleId(), f.path(), f.message(), "policy"));
            }
        }
        for (FileChange c : all) {
            if (c.path == null || !c.path.endsWith(".java") || c.path.startsWith("src/test/")) {
                continue;
            }
            String text = c.introducedText();
            SMELLS.forEach((p, sev) -> {
                if (p.matcher(text).find()) {
                    findings.add(finding(sev[0], "SMELL", c.path, sev[1], "static"));
                }
            });
        }
        if (ctx.useLlm() && !all.isEmpty()) {
            result.llmCalls++;
            StringBuilder code = new StringBuilder();
            for (FileChange c : all) {
                if (code.length() < 40_000 && c.path != null && c.path.startsWith("src/main/")) {
                    code.append(LlmClient.untrusted(c.path, c.introducedText())).append('\n');
                }
            }
            ctx.llm().complete("You are an application-security reviewer.",
                    "Review this change for OWASP Top 10 issues. Return JSON {\"findings\": [{\"severity\": \"LOW|MEDIUM\", "
                            + "\"path\": \"...\", \"message\": \"...\"}]}. Advisory only.\n" + code, 2000)
                    .map(Json::extractObject).ifPresent(m -> {
                        for (Object o : Json.list(m.get("findings"))) {
                            Map<String, Object> f = Json.obj(o);
                            // Advisory findings are capped at MEDIUM so the model can't block or unblock a release by itself.
                            String sev = "LOW".equals(Json.str(f, "severity")) ? "LOW" : "MEDIUM";
                            findings.add(finding(sev, "LLM-ADVISORY", Json.str(f, "path"), Json.str(f, "message"), "llm"));
                        }
                    });
            result.mode = "llm";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reviewedFiles", all.size());
        out.put("findings", findings);
        out.put("blocking", findings.stream().filter(f -> List.of("HIGH", "CRITICAL").contains(f.get("severity"))).count());
        result.output("security-findings", Json.pretty(out));
        result.decision("Security review of " + all.size() + " files: " + findings.size() + " findings",
                "policy rules re-applied to the final change set plus static smell checks", List.of());
        return result;
    }

    private static Map<String, Object> finding(String severity, String rule, String path, String message, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", severity);
        m.put("rule", rule);
        m.put("path", path);
        m.put("message", message);
        m.put("source", source);
        return m;
    }
}
