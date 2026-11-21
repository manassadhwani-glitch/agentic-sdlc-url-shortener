package com.example.shortener.api;

import com.example.shortener.analytics.ClickRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP tests against a real Spring context, H2 and Flyway migrations.
 * {@code @AutoConfigureObservability} re-enables metrics export, which Spring Boot turns off in tests by default,
 * so the Prometheus endpoint can be checked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class UrlApiIntegrationTest {

    private static final String KEY_A = "test-key-a";
    private static final String KEY_B = "test-key-b";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ClickRecorder clickRecorder;

    private ResultActions create(String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/urls").header("X-API-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String createAndGetCode(String key, String body) throws Exception {
        String response = create(key, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("shortCode").asText();
    }

    private static String alias() {
        return "it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createThenRedirect() throws Exception {
        create(KEY_A, "{\"longUrl\":\"https://example.com/docs?page=1\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/v1/urls/[0-9A-Za-z]{7}")))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.shortCode").value(matchesPattern("[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.shortUrl").value(matchesPattern("http://localhost:8080/[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.longUrl").value("https://example.com/docs?page=1"));

        String code = createAndGetCode(KEY_A, "{\"longUrl\":\"https://example.com/x\"}");
        mvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/x"))
                .andExpect(header().string("Cache-Control", "private, max-age=0"));
    }

    @Test
    void customAliasAndConflict() throws Exception {
        String alias = alias();
        create(KEY_A, "{\"longUrl\":\"https://example.com\",\"customAlias\":\"" + alias + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value(alias));

        create(KEY_B, "{\"longUrl\":\"https://other.example\",\"customAlias\":\"" + alias + "\"}")
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://errors.shortener.example/alias-taken"));
    }

    @Test
    void rejectsMissingOrWrongApiKey() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content("{\"longUrl\":\"https://e.com\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://errors.shortener.example/unauthorized"));
        create("wrong-key", "{\"longUrl\":\"https://e.com\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void validationErrorsAreProblemDetails() throws Exception {
        create(KEY_A, "{\"longUrl\":\"http://169.254.169.254/latest/meta-data\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://errors.shortener.example/invalid-url"));
        create(KEY_A, "{\"longUrl\":\"\"}").andExpect(status().isBadRequest());
        create(KEY_A, "{\"longUrl\":\"https://e.com\",\"customAlias\":\"abc1234\"}").andExpect(status().isBadRequest());
        create(KEY_A, "not json").andExpect(status().isBadRequest());
    }

    @Test
    void idempotentCreateReplaysSameResult() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"longUrl\":\"https://example.com/idem\"}";

        String first = mvc.perform(post("/api/v1/urls").header("X-API-Key", KEY_A).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(post("/api/v1/urls").header("X-API-Key", KEY_A).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second).get("shortCode")).isEqualTo(json.readTree(first).get("shortCode"));

        // Same key, different body => 422
        mvc.perform(post("/api/v1/urls").header("X-API-Key", KEY_A).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"longUrl\":\"https://example.com/other\"}"))
                .andExpect(status().isUnprocessableEntity());

        // Keys are scoped per API key: tenant B can use the same value independently
        mvc.perform(post("/api/v1/urls").header("X-API-Key", KEY_B).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));
    }

    @Test
    void rejectsMalformedIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/urls").header("X-API-Key", KEY_A).header("Idempotency-Key", "has spaces!")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"longUrl\":\"https://e.com\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCodeIs404() throws Exception {
        mvc.perform(get("/no-such-code")).andExpect(status().isNotFound());
        mvc.perform(get("/ab")).andExpect(status().isNotFound());
    }

    @Test
    void disableMakesRedirectGoneAndIsOwnerOnly() throws Exception {
        String code = createAndGetCode(KEY_A, "{\"longUrl\":\"https://example.com/temp\"}");

        mvc.perform(delete("/api/v1/urls/" + code).header("X-API-Key", KEY_B)).andExpect(status().isNotFound());
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        mvc.perform(delete("/api/v1/urls/" + code).header("X-API-Key", KEY_A)).andExpect(status().isNoContent());
        mvc.perform(get("/" + code)).andExpect(status().isGone());
        mvc.perform(get("/api/v1/urls/" + code).header("X-API-Key", KEY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    void metadataIsTenantScoped() throws Exception {
        String code = createAndGetCode(KEY_A, "{\"longUrl\":\"https://example.com/meta\"}");
        mvc.perform(get("/api/v1/urls/" + code).header("X-API-Key", KEY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://example.com/meta"))
                .andExpect(jsonPath("$.customAlias").value(false));
        mvc.perform(get("/api/v1/urls/" + code).header("X-API-Key", KEY_B)).andExpect(status().isNotFound());
    }

    @Test
    void statsAggregateClicks() throws Exception {
        String code = createAndGetCode(KEY_A, "{\"longUrl\":\"https://example.com/stats\"}");

        String chrome = "Mozilla/5.0 (Macintosh) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/" + code).header("Referer", "https://news.example.com/post/1").header("User-Agent", chrome));
        }
        mvc.perform(get("/" + code).header("User-Agent", "curl/8.4.0"));
        clickRecorder.flush();

        String body = mvc.perform(get("/api/v1/urls/" + code + "/stats").header("X-API-Key", KEY_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(4))
                .andExpect(jsonPath("$.daily", hasSize(1)))
                .andExpect(jsonPath("$.daily[0].clicks").value(4))
                .andExpect(jsonPath("$.topReferrers[0].key").value("news.example.com"))
                .andExpect(jsonPath("$.topReferrers[0].clicks").value(3))
                .andReturn().getResponse().getContentAsString();

        JsonNode uas = json.readTree(body).get("userAgents");
        assertThat(uas.toString()).contains("Chrome").contains("CLI/Library");
    }

    @Test
    void statsRejectsBadRanges() throws Exception {
        String code = createAndGetCode(KEY_A, "{\"longUrl\":\"https://example.com/r\"}");
        mvc.perform(get("/api/v1/urls/" + code + "/stats?from=2026-02-01&to=2026-01-01").header("X-API-Key", KEY_A))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/urls/" + code + "/stats?from=2020-01-01&to=2026-01-01").header("X-API-Key", KEY_A))
                .andExpect(status().isBadRequest());
    }

    @Test
    void healthAndMetricsAreExposed() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }
}
