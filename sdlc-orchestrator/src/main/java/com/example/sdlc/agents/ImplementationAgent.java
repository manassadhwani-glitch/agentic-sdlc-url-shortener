package com.example.sdlc.agents;

import com.example.sdlc.json.Json;
import com.example.sdlc.llm.LlmClient;
import com.example.sdlc.model.FileChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Produces the code change set for features not yet present in the workspace.
 *
 * <p>LLM mode: the model gets the spec, design, feedback and the impacted files (fenced as untrusted data) and must
 * return a JSON change set of exact find/replace edits. Deterministic / fallback mode: the verified playbook.
 * Either way the output is only a <i>proposal</i>; policy, approvals and tests decide whether it lands.
 *
 * <p>Fault injection ({@code implement:naive-first-attempt}): the first attempt uses a known-buggy variant, so the
 * verify → rollback → rework path can be shown deterministically. It is labelled as injected in the audit trail.
 */
public final class ImplementationAgent implements Agent {

    private static final int MAX_CONTEXT_CHARS = 60_000;

    @Override
    public String name() {
        return "implementation";
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        AgentResult result = new AgentResult();
        Map<String, Object> spec = ctx.inputJson("spec");
        List<Catalog.Feature> todo = new ArrayList<>();
        for (String id : Json.strings(spec.get("features"))) {
            Catalog.Feature f = ctx.catalog().feature(id);
            if (!f.presentIn(ctx.workspace())) {
                todo.add(f);
            }
        }
        if (todo.isEmpty()) {
            result.decision("No code change required", "all requested features are already present in the workspace", List.of());
            return result;
        }

        if (ctx.useLlm()) {
            result.llmCalls++;
            Optional<List<FileChange>> llm = llmChanges(ctx, spec, todo);
            if (llm.isPresent()) {
                result.mode = "llm";
                result.changes.addAll(llm.get());
                result.decision("Model-generated change set (" + llm.get().size() + " files)",
                        "generated from spec + design + impacted files; subject to the same policy, approval and test gates",
                        List.of("verified playbook"));
                return result;
            }
            result.mode = "fallback";
        } else if (ctx.fallbackMode()) {
            result.mode = "fallback";
        }

        boolean referenceDone = false;
        for (Catalog.Feature f : todo) {
            if ("reference".equals(f.playbook())) {
                if (!referenceDone) {
                    result.changes.addAll(Playbooks.fromReference(ctx.referenceDir(),
                            p -> p.startsWith("src/main/") || p.equals("pom.xml") || p.equals("README.md"), ctx));
                    referenceDone = true;
                }
                continue;
            }
            String playbook = f.playbook();
            boolean injectNaive = ctx.fault("naive-first-attempt") && ctx.feedback().isEmpty()
                    && Playbooks.exists(playbook + ".naive");
            result.changes.addAll(Playbooks.load(injectNaive ? playbook + ".naive" : playbook));
            if (injectNaive) {
                result.decision("[FAULT INJECTION] first attempt for " + f.id() + " uses the naive variant",
                        "demonstrates detection by tests, journal rollback and rework with feedback", List.of());
            } else if (!ctx.feedback().isEmpty()) {
                result.decision("Revised " + f.id() + " after feedback",
                        "previous attempt failed: " + abbreviate(String.join(" | ", ctx.feedback())), List.of());
            }
        }
        result.decision("Change set for " + todo.stream().map(Catalog.Feature::id).toList() + " (" + result.changes.size() + " files)",
                "verified playbook implementation", List.of());
        return result;
    }

    private Optional<List<FileChange>> llmChanges(AgentContext ctx, Map<String, Object> spec, List<Catalog.Feature> todo) {
        StringBuilder files = new StringBuilder();
        for (Catalog.Feature f : todo) {
            for (String path : ctx.analyzer().filesFor(f.components())) {
                if (files.length() > MAX_CONTEXT_CHARS) {
                    break;
                }
                files.append(LlmClient.untrusted(path, ctx.workspace().read(path))).append('\n');
            }
        }
        String system = "You are a senior Java/Spring Boot engineer. You change code via minimal, exact edits.";
        String user = "Implement the acceptance criteria. Return JSON {\"changes\": [{\"path\": \"...\", \"op\": \"CREATE|EDIT\", "
                + "\"content\": \"(CREATE only)\", \"edits\": [{\"find\": \"exact unique existing text\", \"replace\": \"new text\"}]}]}. "
                + "Only touch src/main/**. Schema changes must be new Flyway files. No secrets, no Runtime.exec, no string-built SQL.\n"
                + LlmClient.untrusted("spec", Json.pretty(spec)) + "\n" + LlmClient.untrusted("design", ctx.input("design")) + "\n"
                + (ctx.feedback().isEmpty() ? "" : "Previous attempt failed with:\n" + String.join("\n", ctx.feedback()) + "\n")
                + files;
        return ctx.llm().complete(system, user, 8000).map(Json::extractObject)
                .map(m -> m == null ? List.<FileChange>of() : FileChange.listFromJson(m.get("changes")))
                .filter(list -> !list.isEmpty() && list.stream().allMatch(c -> c.path != null && c.op != null));
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
