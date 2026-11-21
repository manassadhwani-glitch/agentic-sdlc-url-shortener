package com.example.shortener.analytics;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Pseudonymises client IPs with a keyed hash (HMAC-SHA256), so raw IPs are never stored.
 * The salt is a secret: without it, the IPv4 space (2^32) could be brute-forced from plain hashes.
 */
public class IpHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec key;

    public IpHasher(String salt) {
        if (salt == null || salt.isBlank()) {
            throw new IllegalArgumentException("ip hash salt must be configured");
        }
        this.key = new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String hash(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM); // Mac is not thread-safe; one per call is cheap enough
            mac.init(key);
            byte[] digest = mac.doFinal(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
