package com.example.shortener.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Bean Validation does the cheap shape checks here. The semantic rules (scheme, host, SSRF, alias namespace)
 * live in {@code UrlValidator} and {@code AliasPolicy}, so they also apply to callers that don't go through HTTP.
 */
public record CreateUrlRequest(
        @NotBlank @Size(max = 2048) String longUrl,
        @Size(min = 3, max = 64) String customAlias) {

    /** Treats a blank alias as "no alias". */
    public String normalizedAlias() {
        return customAlias == null || customAlias.isBlank() ? null : customAlias.trim();
    }
}
