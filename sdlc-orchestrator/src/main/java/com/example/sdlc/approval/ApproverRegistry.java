package com.example.sdlc.approval;

import com.example.sdlc.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who may approve what. Loaded from {@code approvers.json} (classpath default, or a file you pass in).
 *
 * <p>Prototype limitation: the CLI trusts {@code --as <name>}, so there is no authentication. In production
 * identity comes from SSO (OIDC), and approvals would be signed, for example as PR reviews on a protected branch.
 */
public final class ApproverRegistry {

    private final Map<String, List<String>> rolesByUser;

    public ApproverRegistry(Map<String, List<String>> rolesByUser) {
        this.rolesByUser = rolesByUser;
    }

    public static ApproverRegistry load(Path file) {
        try {
            String text;
            if (file != null && Files.exists(file)) {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } else {
                try (InputStream in = ApproverRegistry.class.getResourceAsStream("/approvers.json")) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            Map<String, List<String>> roles = new LinkedHashMap<>();
            Json.obj(Json.parseObject(text).get("approvers")).forEach((user, r) -> roles.put(user, Json.strings(r)));
            return new ApproverRegistry(roles);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean hasRole(String user, String role) {
        return rolesByUser.getOrDefault(user, List.of()).contains(role);
    }

    public boolean isKnown(String user) {
        return rolesByUser.containsKey(user);
    }

    public Map<String, List<String>> all() {
        return rolesByUser;
    }
}
