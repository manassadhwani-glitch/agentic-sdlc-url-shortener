package com.example.sdlc.llm;

import java.util.Optional;

/** Used when no API key is configured: always returns empty, so agents run their deterministic logic. */
public final class OfflineLlm implements LlmClient {

    @Override
    public Optional<String> complete(String system, String user, int maxTokens) {
        return Optional.empty();
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String describe() {
        return "offline (deterministic agents)";
    }
}
