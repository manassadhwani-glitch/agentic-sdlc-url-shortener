package com.example.shortener.security;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes RFC 7807 problem+json from servlet filters, which run before Spring MVC's exception handling.
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static void write(HttpServletResponse response, int status, String slug, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"type\":\"https://errors.shortener.example/" + slug + "\","
                + "\"title\":\"" + escape(title) + "\","
                + "\"status\":" + status + ","
                + "\"detail\":\"" + escape(detail) + "\"}";
        response.getWriter().write(body);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
