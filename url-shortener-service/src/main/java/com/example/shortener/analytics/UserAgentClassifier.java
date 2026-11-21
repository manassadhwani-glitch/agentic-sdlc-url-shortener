package com.example.shortener.analytics;

import java.util.Locale;

/**
 * Coarse user-agent families. We deliberately keep only the family, never the full UA string,
 * which reduces fingerprinting risk (data minimisation).
 */
public final class UserAgentClassifier {

    private UserAgentClassifier() {
    }

    public static String classify(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider") || ua.contains("slurp")) {
            return "Bot";
        }
        if (ua.startsWith("curl/") || ua.startsWith("wget/") || ua.contains("python-requests") || ua.contains("okhttp")) {
            return "CLI/Library";
        }
        // Order matters: Edge and Opera UAs contain "chrome", and Chrome UAs contain "safari".
        if (ua.contains("edg/")) {
            return "Edge";
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("firefox/")) {
            return "Firefox";
        }
        if (ua.contains("chrome/") || ua.contains("crios/")) {
            return "Chrome";
        }
        if (ua.contains("safari/")) {
            return "Safari";
        }
        return "Other";
    }
}
