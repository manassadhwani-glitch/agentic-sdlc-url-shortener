package com.example.sdlc.store;

import com.example.sdlc.json.Json;
import com.example.sdlc.model.RunState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Durable run state on the file system: {@code <root>/<runId>/state.json}.
 * Writes are atomic (temp file + atomic move), so a crash mid-write never leaves a corrupt state file.
 */
public final class RunStore {

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final Path root;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RunStore(Path root, Clock clock) {
        this.root = root;
        this.clock = clock;
    }

    public Path root() {
        return root;
    }

    public String newRunId(String scenario) {
        byte[] b = new byte[2];
        random.nextBytes(b);
        String slug = scenario == null ? "adhoc" : scenario.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase();
        return "run-" + ID_TIME.format(clock.instant()) + "-" + slug + "-" + HexFormat.of().formatHex(b);
    }

    public Path runDir(String runId) {
        if (!runId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid run id");
        }
        return root.resolve(runId);
    }

    public synchronized void save(RunState run) {
        try {
            Path dir = runDir(run.runId);
            Files.createDirectories(dir);
            Path tmp = dir.resolve("state.json.tmp");
            Files.writeString(tmp, Json.pretty(run), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot persist run state", e);
        }
    }

    public RunState load(String runId) {
        Path file = runDir(runId).resolve("state.json");
        try {
            return RunState.fromJson(Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("no such run: " + runId, e);
        }
    }

    public List<String> list() {
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return ids;
        }
        try (Stream<Path> s = Files.list(root)) {
            s.filter(p -> Files.exists(p.resolve("state.json"))).map(p -> p.getFileName().toString()).sorted().forEach(ids::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ids;
    }
}
