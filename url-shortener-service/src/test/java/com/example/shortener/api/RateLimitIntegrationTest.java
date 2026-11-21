package com.example.shortener.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Separate context with tiny limits, so throttling is deterministic. */
@SpringBootTest(properties = {
        "shortener.rate-limit.api-capacity=2",
        "shortener.rate-limit.api-refill-per-second=0.001",
        "shortener.rate-limit.redirect-capacity=2",
        "shortener.rate-limit.redirect-refill-per-second=0.001"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void apiCallsAreLimitedPerKey() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/urls").header("X-API-Key", "test-key-a")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"longUrl\":\"https://example.com\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/v1/urls").header("X-API-Key", "test-key-a")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"longUrl\":\"https://example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("https://errors.shortener.example/rate-limited"));

        // A different key has its own bucket
        mvc.perform(post("/api/v1/urls").header("X-API-Key", "test-key-b")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"longUrl\":\"https://example.com\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void redirectsAreLimitedPerIpButHealthIsNot() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/unknown-code").with(r -> { r.setRemoteAddr("198.51.100.20"); return r; }))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/unknown-code").with(r -> { r.setRemoteAddr("198.51.100.20"); return r; }))
                .andExpect(status().isTooManyRequests());

        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }
}
