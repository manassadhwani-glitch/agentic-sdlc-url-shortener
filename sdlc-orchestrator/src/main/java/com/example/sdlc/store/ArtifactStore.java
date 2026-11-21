package com.example.sdlc.store;

import com.example.sdlc.model.ArtifactMeta;
import com.example.sdlc.model.RunState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Versioned, content-addressed artifacts. A new version is created only when content changes (different SHA-256).
 * That is what makes re-planning cheap: if an upstream stage re-runs and produces identical output, nothing downstream
 * is invalidated.
 */
public final class ArtifactStore {

    private final RunStore runs;
    private final Clock clock;

    public ArtifactStore(RunStore runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    public record WriteResult(ArtifactMeta meta, boolean changed) {
    }

    public synchronized WriteResult write(RunState run, String name, String content, String producedBy, int attempt,
                                          String mode, Map<String, Integer> inputs) {
        String sha = sha256(content);
        Optional<ArtifactMeta> latest = run.latest(name);
        if (latest.isPresent() && latest.get().sha256.equals(sha)) {
            return new WriteResult(latest.get(), false);
        }
        ArtifactMeta meta = new ArtifactMeta();
        meta.name = name;
        meta.version = latest.map(m -> m.version + 1).orElse(1);
        meta.sha256 = sha;
        meta.producedBy = producedBy;
        meta.attempt = attempt;
        meta.mode = mode;
        meta.inputs.putAll(inputs);
        meta.createdAt = clock.instant().toString();
        meta.file = "artifacts/" + name + ".v" + meta.version + extension(name);
        try {
            Path file = runs.runDir(run.runId).resolve(meta.file);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write artifact " + name, e);
        }
        run.artifacts.computeIfAbsent(name, k -> new ArrayList<>()).add(meta);
        return new WriteResult(meta, true);
    }

    public Optional<String> read(RunState run, String name) {
        return run.latest(name).map(m -> read(run, m));
    }

    public String read(RunState run, ArtifactMeta meta) {
        try {
            return Files.readString(runs.runDir(run.runId).resolve(meta.file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read artifact " + meta.ref(), e);
        }
    }

    static String extension(String name) {
        return name.endsWith("-json") || name.equals("spec") || name.equals("plan") || name.equals("proposed-changes")
                || name.equals("test-report") || name.equals("security-findings") || name.equals("impact")
                || name.equals("test-cases") || name.equals("migration-check") ? ".json" : ".md";
    }

    public static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
