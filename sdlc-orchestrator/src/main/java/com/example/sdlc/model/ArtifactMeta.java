package com.example.sdlc.model;

import com.example.sdlc.json.Json;
import com.example.sdlc.json.JsonWritable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One immutable version of an artifact plus its lineage: who produced it, in which mode, and exactly which
 * input versions it was derived from. This is what lets us answer "why does this line of code exist?".
 */
public final class ArtifactMeta implements JsonWritable {

    public String name;
    public int version;
    public String sha256;
    public String producedBy;
    public int attempt;
    /** "llm" | "deterministic" | "fallback" | "human". */
    public String mode;
    /** input artifact name → version it was derived from. */
    public Map<String, Integer> inputs = new LinkedHashMap<>();
    public String createdAt;
    public String file;

    public String ref() {
        return name + "@v" + version;
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("version", version);
        m.put("sha256", sha256);
        m.put("producedBy", producedBy);
        m.put("attempt", attempt);
        m.put("mode", mode);
        m.put("inputs", inputs);
        m.put("createdAt", createdAt);
        m.put("file", file);
        return m;
    }

    public static ArtifactMeta fromJson(Map<String, Object> m) {
        ArtifactMeta a = new ArtifactMeta();
        a.name = Json.str(m, "name");
        a.version = (int) Json.num(m, "version", 1);
        a.sha256 = Json.str(m, "sha256");
        a.producedBy = Json.str(m, "producedBy");
        a.attempt = (int) Json.num(m, "attempt", 1);
        a.mode = Json.str(m, "mode");
        Json.obj(m.get("inputs")).forEach((k, v) -> a.inputs.put(k, ((Number) v).intValue()));
        a.createdAt = Json.str(m, "createdAt");
        a.file = Json.str(m, "file");
        return a;
    }
}
