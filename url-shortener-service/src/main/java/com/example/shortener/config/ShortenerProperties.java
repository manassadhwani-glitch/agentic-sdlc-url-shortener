package com.example.shortener.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/** Typed configuration for the service. Validated at startup so a bad config fails fast. */
@Validated
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        @NotBlank String baseUrl,
        @NotBlank String codeSecret,
        @Valid @NotNull Security security,
        @Valid @NotNull Validation validation,
        @Valid @NotNull Cache cache,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Analytics analytics,
        @Valid @NotNull Idempotency idempotency) {

    public record Security(@NotEmpty List<String> apiKeys) {
    }

    public record Validation(boolean resolveDns) {
    }

    public record Cache(@Positive long maxSize, @NotNull Duration ttl) {
    }

    public record RateLimit(@Positive long apiCapacity, @Positive double apiRefillPerSecond,
                            @Positive long redirectCapacity, @Positive double redirectRefillPerSecond) {
    }

    public record Analytics(@Positive int queueCapacity, @Positive int batchSize,
                            @Positive long flushIntervalMs, @NotBlank String ipHashSalt) {
    }

    public record Idempotency(@NotNull Duration ttl) {
    }

    /** Base URL without a trailing slash. */
    public String normalizedBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
