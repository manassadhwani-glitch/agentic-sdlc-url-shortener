package com.example.sdlc.policy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of one rule. {@code critical} DENY findings (secrets, boundary escapes) stop the run immediately;
 * non-critical DENY findings are fed back to the agent as feedback for its next (bounded) attempt.
 */
public record PolicyFinding(String ruleId, String category, Effect effect, boolean critical, String requiredRole,
                            String path, String message, String remediation) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", ruleId);
        m.put("category", category);
        m.put("effect", effect);
        m.put("critical", critical);
        m.put("requiredRole", requiredRole);
        m.put("path", path);
        m.put("message", message);
        m.put("remediation", remediation);
        return m;
    }

    public String summary() {
        return ruleId + " [" + effect + (critical ? ", CRITICAL" : "") + "] " + message
                + (path == null ? "" : " (" + path + ")")
                + (remediation == null ? "" : " → " + remediation);
    }
}
