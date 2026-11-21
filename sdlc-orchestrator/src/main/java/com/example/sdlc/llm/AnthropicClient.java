package com.example.sdlc.llm;

import com.example.sdlc.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Claude via the Anthropic Messages API, using only {@code java.net.http} (no SDK dependency).
 *
 * <p>Fail-closed: timeouts, non-2xx responses, or parse errors return empty (after one retry for transient errors),
 * and the agent falls back to its deterministic implementation. Nothing the model returns is trusted until it passes
 * the same policy checks and gates as any other change.
 */
public final class AnthropicClient implements LlmClient {

    public static final String KEY_ENV = "ANTHROPIC_API_KEY";
    public static final String MODEL_ENV = "ORCH_LLM_MODEL";
    private static final String DEFAULT_MODEL = "claude-sonnet-5-5";
    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");

    private final String apiKey;
    private final String model;
    private final HttpClient http;
    private final Duration timeout;
    private final AtomicInteger calls = new AtomicInteger();
    private final int maxCalls;

    public AnthropicClient(String apiKey, String model, Duration timeout, int maxCalls) {
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        this.timeout = timeout;
        this.maxCalls = maxCalls;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Returns a Claude client if ANTHROPIC_API_KEY is set, otherwise the offline client. */
    public static LlmClient fromEnvironment(int maxCalls) {
        String key = System.getenv(KEY_ENV);
        if (key == null || key.isBlank()) {
            return new OfflineLlm();
        }
        return new AnthropicClient(key, System.getenv(MODEL_ENV), Duration.ofSeconds(90), maxCalls);
    }

    @Override
    public Optional<String> complete(String system, String user, int maxTokens) {
        if (calls.incrementAndGet() > maxCalls) {
            return Optional.empty(); // budget guard: cost control is a safety control
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", system + "\n\n" + GUARD);
        body.put("messages", List.of(Map.of("role", "user", "content", user)));
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(timeout)
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    Thread.sleep(1000L * attempt);
                    continue;
                }
                if (status / 100 != 2) {
                    return Optional.empty();
                }
                Map<String, Object> json = Json.parseObject(response.body());
                StringBuilder text = new StringBuilder();
                for (Object block : Json.list(json.get("content"))) {
                    Map<String, Object> b = Json.obj(block);
                    if ("text".equals(b.get("type"))) {
                        text.append(Json.str(b, "text", ""));
                    }
                }
                return text.length() == 0 ? Optional.empty() : Optional.of(text.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception e) {
                if (attempt == 2) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isAvailable() {
        return calls.get() < maxCalls;
    }

    @Override
    public String describe() {
        return "anthropic:" + model;
    }
}
