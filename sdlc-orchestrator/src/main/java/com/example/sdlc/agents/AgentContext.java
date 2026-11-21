package com.example.sdlc.agents;

import com.example.sdlc.analysis.RepoAnalyzer;
import com.example.sdlc.json.Json;
import com.example.sdlc.llm.LlmClient;
import com.example.sdlc.model.Question;
import com.example.sdlc.model.StageDef;
import com.example.sdlc.tools.TestRunner;
import com.example.sdlc.workspace.GuardedWorkspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything an agent may see for one attempt, as an immutable snapshot taken by the coordinator when the stage
 * starts. Agents get only the artifacts their stage {@code consumes} (least privilege), and read-only workspace
 * access through the guarded workspace.
 */
public record AgentContext(
        String runId,
        String requirement,
        StageDef stage,
        int attempt,
        boolean fallbackMode,
        Map<String, String> inputs,
        Map<String, Integer> inputVersions,
        List<String> feedback,
        List<Question> answers,
        GuardedWorkspace workspace,
        LlmClient llm,
        Catalog catalog,
        Set<String> faults,
        Path referenceDir,
        TestRunner testRunner,
        Map<String, Object> config) {

    public String input(String name) {
        String v = inputs.get(name);
        if (v == null) {
            throw new IllegalStateException("stage " + stage.id + " did not receive input '" + name + "'");
        }
        return v;
    }

    public Map<String, Object> inputJson(String name) {
        return Json.parseObject(input(name));
    }

    public boolean hasInput(String name) {
        return inputs.containsKey(name);
    }

    /** True when the model may be used for this attempt (available and not in fallback mode). */
    public boolean useLlm() {
        return llm.isAvailable() && !fallbackMode;
    }

    public boolean fault(String id) {
        return faults.contains(stage.id + ":" + id);
    }

    public RepoAnalyzer analyzer() {
        return new RepoAnalyzer(workspace);
    }
}
