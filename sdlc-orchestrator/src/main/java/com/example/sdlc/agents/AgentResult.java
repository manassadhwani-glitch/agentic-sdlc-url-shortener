package com.example.sdlc.agents;

import com.example.sdlc.model.FileChange;
import com.example.sdlc.model.Question;
import com.example.sdlc.model.StageDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What an agent proposes. Nothing here has taken effect yet. */
public final class AgentResult {

    public final Map<String, String> outputs = new LinkedHashMap<>();
    public final List<FileChange> changes = new ArrayList<>();
    public final List<Question> questions = new ArrayList<>();
    /** New stages to insert (dynamic re-planning), and extra dependencies for existing stages. */
    public final List<StageDef> proposedStages = new ArrayList<>();
    public final Map<String, List<String>> addDependencies = new LinkedHashMap<>();
    /** Decision records: decision, rationale, alternatives considered. */
    public final List<Map<String, Object>> decisions = new ArrayList<>();
    /** "llm" | "deterministic" | "fallback". */
    public String mode = "deterministic";
    public int llmCalls;

    public AgentResult output(String name, String content) {
        outputs.put(name, content);
        return this;
    }

    public AgentResult decision(String decision, String rationale, List<String> alternatives) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("decision", decision);
        d.put("rationale", rationale);
        d.put("alternatives", alternatives);
        decisions.add(d);
        return this;
    }
}
