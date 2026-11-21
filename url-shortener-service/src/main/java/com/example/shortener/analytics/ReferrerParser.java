package com.example.shortener.analytics;

import java.net.URI;
import java.util.Locale;

/** Keeps only the referrer host (not path or query, which can contain personal data). */
public final class ReferrerParser {

    public static final String DIRECT = "(direct)";
    public static final String INVALID = "(invalid)";
    private static final int MAX = 255;

    private ReferrerParser() {
    }

    public static String host(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return DIRECT;
        }
        try {
            String host = URI.create(referrer.trim()).getHost();
            if (host == null || host.isBlank()) {
                return INVALID;
            }
            String h = host.toLowerCase(Locale.ROOT);
            return h.length() > MAX ? h.substring(0, MAX) : h;
        } catch (IllegalArgumentException e) {
            return INVALID;
        }
    }
}
