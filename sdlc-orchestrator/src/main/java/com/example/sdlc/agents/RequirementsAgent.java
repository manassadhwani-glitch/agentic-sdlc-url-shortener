package com.example.sdlc.agents;

import com.example.sdlc.json.Json;
import com.example.sdlc.llm.LlmClient;
import com.example.sdlc.model.Question;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a raw requirement into a normalized, testable spec: features, numbered acceptance criteria (each tied to
 * the tests that will prove it), assumptions, out-of-scope items and open questions.
 *
 * <p>Ambiguity handling: vague phrasing ("safer", "more stuff") produces clarifying questions, and the stage pauses
 * for a human instead of guessing. Compliance: if the compliance gate rejects a statement (e.g. storing raw IPs), the
 * next attempt rewrites it to the compliant alternative and records the decision.
 *
 * <p>In LLM mode the model interprets intent but must choose from catalog features, so acceptance criteria stay
 * grounded in real, verifiable tests (constrained generation).
 */
public final class RequirementsAgent implements Agent {

    @Override
    public String name() {
        return "requirements";
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        AgentResult result = new AgentResult();
        Catalog catalog = ctx.catalog();
        String requirement = ctx.input("requirement");
        Map<String, Question> answered = new LinkedHashMap<>();
        for (Question q : ctx.answers()) {
            answered.put(q.id, q);
        }

        Set<String> featureIds = new LinkedHashSet<>();
        List<String> statements = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        List<String> outOfScope = new ArrayList<>();
        statements.add(requirement.strip());

        // 1) Ambiguity detection → questions (unless already answered)
        List<Question> open = new ArrayList<>();
        for (Catalog.Ambiguity a : catalog.ambiguities()) {
            if (!a.matches(requirement)) {
                continue;
            }
            String qid = "Q-" + a.id();
            Question q = answered.get(qid);
            if (q == null) {
                Question nq = new Question();
                nq.id = qid;
                nq.stageId = ctx.stage().id;
                nq.text = a.question();
                for (Catalog.Option o : a.options()) {
                    nq.options.add(o.label());
                }
                open.add(nq);
                continue;
            }
            Catalog.Option chosen = choose(a, q.answer);
            if (chosen == null) {
                statements.add("Clarified by " + q.answeredBy + ": " + q.answer);
                catalog.match(q.answer).forEach(f -> featureIds.add(f.id()));
            } else {
                statements.add("Clarified by " + q.answeredBy + ": " + chosen.requirementText());
                featureIds.addAll(chosen.features());
                if (chosen.features().isEmpty()) {
                    outOfScope.add(chosen.requirementText());
                }
            }
        }

        // 2) LLM interpretation (optional), constrained to catalog features
        if (ctx.useLlm() && open.isEmpty()) {
            Optional<Map<String, Object>> llm = interpretWithLlm(ctx, requirement, statements);
            result.llmCalls++;
            if (llm.isPresent()) {
                result.mode = "llm";
                Json.strings(llm.get().get("features")).stream()
                        .filter(id -> catalog.features().stream().anyMatch(f -> f.id().equals(id)))
                        .forEach(featureIds::add);
                assumptions.addAll(Json.strings(llm.get().get("assumptions")));
            } else {
                result.mode = "fallback";
            }
        }

        if (!open.isEmpty()) {
            result.questions.addAll(open);
            result.decision("Pause for clarification instead of guessing",
                    "the requirement contains vague terms; guessing would risk building the wrong thing",
                    List.of("assume the most common interpretation", "implement all interpretations"));
            return result;
        }

        // 3) Keyword features from the raw requirement
        catalog.match(requirement).forEach(f -> featureIds.add(f.id()));

        // 4) Compliance self-correction: rewrite statements rejected by the compliance gate on the previous attempt
        boolean complianceFeedback = ctx.feedback().stream().anyMatch(f -> f.contains("CMP-001"));
        if (complianceFeedback) {
            List<String> rewritten = new ArrayList<>();
            for (String s : statements) {
                String lower = s.toLowerCase(Locale.ROOT);
                if (lower.contains("raw ip") || lower.contains("ip address of every visitor")) {
                    rewritten.add("Visitor IP addresses are never stored raw; only a salted HMAC is kept (policy CMP-001)");
                    assumptions.add("A request to store raw visitor IPs was replaced by the compliant pseudonymised form required by policy CMP-001");
                    featureIds.add("privacy");
                    result.decision("Replace raw-IP storage with pseudonymised hashing",
                            "compliance policy CMP-001 forbids storing personal data in identifiable form; hashing still supports unique-visitor counts",
                            List.of("store raw IPs with a retention limit (still PII)", "drop the request entirely"));
                } else {
                    rewritten.add(s);
                }
            }
            statements = rewritten;
        }

        if (featureIds.isEmpty()) {
            Question q = new Question();
            q.id = "Q-scope";
            q.stageId = ctx.stage().id;
            q.text = "I could not map this requirement to a known capability. What should the change do?";
            result.questions.add(q);
            return result;
        }

        // 5) Normalized spec
        List<Map<String, Object>> criteria = new ArrayList<>();
        int n = 1;
        for (String id : featureIds) {
            Catalog.Feature f = catalog.feature(id);
            for (Catalog.Criterion c : f.criteria()) {
                Map<String, Object> ac = new LinkedHashMap<>();
                ac.put("id", "AC-" + n++);
                ac.put("feature", f.id());
                ac.put("text", c.text());
                ac.put("verifiedBy", c.verifiedBy());
                criteria.add(ac);
            }
        }
        if (outOfScope.isEmpty()) {
            outOfScope.add("Multi-region deployment, user accounts and a web UI");
        }
        assumptions.add("Single-node deployment; horizontal scaling is a documented follow-up");

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", "REQ-" + ctx.runId().substring(Math.max(0, ctx.runId().length() - 4)).toUpperCase(Locale.ROOT));
        spec.put("status", "READY");
        spec.put("summary", requirement.strip());
        spec.put("statements", statements);
        spec.put("features", new ArrayList<>(featureIds));
        spec.put("acceptanceCriteria", criteria);
        spec.put("assumptions", assumptions);
        spec.put("outOfScope", outOfScope);
        spec.put("openQuestions", List.of());
        result.output("spec", Json.pretty(spec));
        result.decision("Normalized requirement into " + criteria.size() + " acceptance criteria across features " + featureIds,
                "each criterion is tied to named tests, so 'done' is checked by the ac-traceability gate, not asserted",
                List.of("free-text criteria without test mapping"));
        return result;
    }

    private static Catalog.Option choose(Catalog.Ambiguity a, String answer) {
        if (answer == null) {
            return null;
        }
        String t = answer.trim();
        if (t.matches("\\d+")) {
            int i = Integer.parseInt(t) - 1;
            return i >= 0 && i < a.options().size() ? a.options().get(i) : null;
        }
        for (Catalog.Option o : a.options()) {
            if (o.label().equalsIgnoreCase(t) || o.label().toLowerCase(Locale.ROOT).startsWith(t.toLowerCase(Locale.ROOT))) {
                return o;
            }
        }
        return null;
    }

    private Optional<Map<String, Object>> interpretWithLlm(AgentContext ctx, String requirement, List<String> statements) {
        StringBuilder features = new StringBuilder();
        for (Catalog.Feature f : ctx.catalog().features()) {
            features.append("- ").append(f.id()).append(": ").append(f.title()).append('\n');
        }
        String system = "You are a senior business analyst normalizing software requirements for a URL shortener.";
        String user = "Pick the catalog features this requirement asks for. Return JSON: "
                + "{\"features\": [ids], \"assumptions\": [strings]}.\nCatalog:\n" + features
                + "\n" + LlmClient.untrusted("requirement", String.join("\n", statements));
        return ctx.llm().complete(system, user, 800).map(Json::extractObject)
                .filter(m -> m != null && m.get("features") instanceof List);
    }
}
