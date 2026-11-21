package com.example.shortener.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** API key checks. Raw keys are never stored on entities; links record a short key id (hash prefix). */
public final class ApiKeys {

    private final List<byte[]> keyDigests;

    public ApiKeys(List<String> configuredKeys) {
        this.keyDigests = configuredKeys.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(ApiKeys::sha256)
                .toList();
        if (keyDigests.isEmpty()) {
            throw new IllegalArgumentException("at least one API key must be configured");
        }
    }

    /** Constant-time check: compares digests, never stops early, and so leaks no timing information. */
    public boolean isValid(String presented) {
        if (presented == null || presented.isBlank()) {
            return false;
        }
        byte[] digest = sha256(presented);
        boolean match = false;
        for (byte[] known : keyDigests) {
            match |= MessageDigest.isEqual(digest, known);
        }
        return match;
    }

    /** Stable, non-reversible identifier for a key (first 16 hex chars of its SHA-256). */
    public static String keyId(String key) {
        return HexFormat.of().formatHex(sha256(key)).substring(0, 16);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
