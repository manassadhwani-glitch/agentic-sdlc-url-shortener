package com.example.sdlc.agents;

import com.example.sdlc.json.Json;
import com.example.sdlc.model.FileChange;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Verified change sets used by the deterministic path, and as the fallback when model output keeps failing.
 * "reference" copies the hand-written reference service; named playbooks are JSON change sets in
 * {@code /playbooks}.
 */
public final class Playbooks {

    private Playbooks() {
    }

    public static boolean exists(String name) {
        return Playbooks.class.getResource("/playbooks/" + name + ".json") != null;
    }

    public static List<FileChange> load(String name) {
        try (InputStream in = Playbooks.class.getResourceAsStream("/playbooks/" + name + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("no playbook " + name);
            }
            return FileChange.listFromJson(Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).get("changes"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** CREATE changes for every reference file matching the filter that the workspace doesn't have yet. */
    public static List<FileChange> fromReference(Path referenceDir, Predicate<String> include, AgentContext ctx) {
        List<FileChange> out = new ArrayList<>();
        if (referenceDir == null || !Files.isDirectory(referenceDir)) {
            throw new IllegalStateException("reference implementation not found: " + referenceDir);
        }
        try (Stream<Path> s = Files.walk(referenceDir)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                String rel = referenceDir.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("target/") || rel.contains("/target/") || !include.test(rel) || ctx.workspace().exists(rel)) {
                    continue;
                }
                out.add(FileChange.create(rel, Files.readString(p, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
