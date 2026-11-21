package com.example.sdlc.agents;

import com.example.sdlc.json.Json;
import com.example.sdlc.workspace.GuardedWorkspace;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Domain knowledge for the deterministic agents: known features (keywords → acceptance criteria → verifying tests →
 * implementation playbook) and known ambiguity patterns (vague phrase → clarifying question with options).
 *
 * <p>This keeps offline runs reproducible and explainable. In LLM mode the model does the interpretation, and the
 * catalog is the safety net it falls back to.
 */
public final class Catalog {

    public record Criterion(String text, List<String> verifiedBy) {
    }

    public record Feature(String id, String title, List<String> keywords, List<Criterion> criteria,
                          List<String> components, boolean needsMigration, String presentIf, String playbook,
                          String testPlaybook) {

        public boolean matches(String text) {
            String t = text.toLowerCase(Locale.ROOT);
            return keywords.stream().anyMatch(t::contains);
        }

        /**
         * Whether the workspace already has this feature. {@code presentIf} is a path, or {@code path::marker}
         * (the file must contain the marker), for features that are edits inside an existing file.
         */
        public boolean presentIn(GuardedWorkspace ws) {
            if (presentIf == null) {
                return false;
            }
            int sep = presentIf.indexOf("::");
            if (sep < 0) {
                return ws.exists(presentIf);
            }
            String content = ws.read(presentIf.substring(0, sep));
            return content != null && content.contains(presentIf.substring(sep + 2));
        }
    }

    public record Option(String label, List<String> features, String requirementText) {
    }

    public record Ambiguity(String id, List<String> terms, String question, List<Option> options) {

        public boolean matches(String text) {
            String t = text.toLowerCase(Locale.ROOT);
            return terms.stream().anyMatch(t::contains);
        }
    }

    private final List<Feature> features;
    private final List<Ambiguity> ambiguities;

    private Catalog(List<Feature> features, List<Ambiguity> ambiguities) {
        this.features = features;
        this.ambiguities = ambiguities;
    }

    public static Catalog load() {
        try (InputStream in = Catalog.class.getResourceAsStream("/catalog.json")) {
            Map<String, Object> doc = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            List<Feature> fs = new ArrayList<>();
            for (Object o : Json.list(doc.get("features"))) {
                Map<String, Object> f = Json.obj(o);
                List<Criterion> cs = new ArrayList<>();
                for (Object c : Json.list(f.get("criteria"))) {
                    Map<String, Object> cm = Json.obj(c);
                    cs.add(new Criterion(Json.str(cm, "text"), Json.strings(cm.get("verifiedBy"))));
                }
                fs.add(new Feature(Json.str(f, "id"), Json.str(f, "title"), Json.strings(f.get("keywords")), cs,
                        Json.strings(f.get("components")), Json.bool(f, "needsMigration", false),
                        Json.str(f, "presentIf"), Json.str(f, "playbook"), Json.str(f, "testPlaybook")));
            }
            List<Ambiguity> as = new ArrayList<>();
            for (Object o : Json.list(doc.get("ambiguities"))) {
                Map<String, Object> a = Json.obj(o);
                List<Option> opts = new ArrayList<>();
                for (Object x : Json.list(a.get("options"))) {
                    Map<String, Object> om = Json.obj(x);
                    opts.add(new Option(Json.str(om, "label"), Json.strings(om.get("features")), Json.str(om, "requirementText")));
                }
                as.add(new Ambiguity(Json.str(a, "id"), Json.strings(a.get("terms")), Json.str(a, "question"), opts));
            }
            return new Catalog(fs, as);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Feature> features() {
        return features;
    }

    public List<Ambiguity> ambiguities() {
        return ambiguities;
    }

    public Feature feature(String id) {
        return features.stream().filter(f -> f.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown feature " + id));
    }

    public List<Feature> match(String text) {
        return features.stream().filter(f -> f.matches(text)).toList();
    }
}
