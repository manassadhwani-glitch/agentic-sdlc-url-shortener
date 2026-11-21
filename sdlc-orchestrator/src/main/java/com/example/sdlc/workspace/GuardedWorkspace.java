package com.example.sdlc.workspace;

import com.example.sdlc.json.Json;
import com.example.sdlc.model.FileChange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The only way agents can change files. Guardrails are enforced here in code, not requested in a prompt:
 * <ul>
 *   <li>paths must stay inside the workspace (no absolute paths, no {@code ..}, no escape through symlinks);</li>
 *   <li>paths must match the stage's declared write scope (least privilege per stage);</li>
 *   <li>every write is journaled first, so it can be undone (saga compensation / rollback).</li>
 * </ul>
 * We use per-change journals instead of {@code git reset} because parallel stages share the workspace: a reset
 * would also wipe the other branch's work.
 */
public final class GuardedWorkspace {

    private static final int MAX_FILES_PER_CHANGESET = 60;
    private final Path root;
    private final Path journalDir;

    public GuardedWorkspace(Path root, Path journalDir) {
        this.root = root.toAbsolutePath().normalize();
        this.journalDir = journalDir;
    }

    public Path root() {
        return root;
    }

    /** Applies changes atomically: either all are applied and journaled, or none are. Returns the journal id. */
    public synchronized String apply(String journalId, List<FileChange> changes, List<String> writeScope) {
        if (changes.size() > MAX_FILES_PER_CHANGESET) {
            throw new BoundaryViolation("change set touches " + changes.size() + " files (max " + MAX_FILES_PER_CHANGESET + ")");
        }
        for (FileChange c : changes) {
            checkWritable(c.path, writeScope);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        try {
            for (FileChange c : changes) {
                Path target = resolve(c.path);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", c.path);
                entry.put("existed", Files.exists(target));
                entry.put("before", Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : null);
                entries.add(entry);
                applyOne(c, target);
            }
        } catch (IOException | RuntimeException e) {
            restore(entries);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new UncheckedIOException((IOException) e);
        }
        writeJournal(journalId, entries);
        return journalId;
    }

    /** Undoes a journal (reverse order). Idempotent: a journal already rolled back is a no-op. */
    public synchronized int rollback(String journalId) {
        Path file = journalDir.resolve(journalId + ".json");
        if (!Files.exists(file)) {
            return 0;
        }
        try {
            Map<String, Object> j = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            if (Json.bool(j, "rolledBack", false)) {
                return 0;
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Object o : Json.list(j.get("entries"))) {
                entries.add(Json.obj(o));
            }
            restore(entries);
            j.put("rolledBack", true);
            Files.writeString(file, Json.pretty(j), StandardCharsets.UTF_8);
            return entries.size();
        } catch (IOException e) {
            throw new UncheckedIOException("rollback failed for " + journalId, e);
        }
    }

    public String read(String relative) {
        try {
            Path p = resolve(relative);
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(String relative) {
        return Files.exists(resolve(relative));
    }

    /** Relative paths of source files (skips build output and VCS folders). */
    public List<String> listFiles() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.startsWith("target/") && !p.contains("/target/") && !p.startsWith(".git/"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void checkWritable(String relative, List<String> writeScope) {
        Path target = resolve(relative);
        String rel = root.relativize(target).toString().replace('\\', '/');
        boolean allowed = false;
        for (String glob : writeScope) {
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            if (m.matches(Path.of(rel))) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new BoundaryViolation("write to '" + rel + "' is outside this stage's write scope " + writeScope);
        }
    }

    Path resolve(String relative) {
        if (relative == null || relative.isBlank() || relative.startsWith("/") || relative.startsWith("\\")
                || relative.matches("^[A-Za-z]:.*")) {
            throw new BoundaryViolation("path must be relative to the workspace: " + relative);
        }
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) {
            throw new BoundaryViolation("path escapes the workspace: " + relative);
        }
        if (!Files.exists(root)) {
            return p; // nothing exists under the root yet, so there are no links to escape through
        }
        try {
            Path existing = p;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing != null && !existing.toRealPath().startsWith(root.toRealPath())) {
                throw new BoundaryViolation("path escapes the workspace via a link: " + relative);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    private void applyOne(FileChange c, Path target) throws IOException {
        switch (c.op) {
            case CREATE -> {
                if (Files.exists(target)) {
                    throw new IllegalStateException("CREATE but file exists: " + c.path);
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, c.content == null ? "" : c.content, StandardCharsets.UTF_8);
            }
            case REPLACE -> {
                Files.createDirectories(target.getParent());
                Files.writeString(target, c.content == null ? "" : c.content, StandardCharsets.UTF_8);
            }
            case EDIT -> {
                if (!Files.exists(target)) {
                    throw new IllegalStateException("EDIT but file missing: " + c.path);
                }
                String text = Files.readString(target, StandardCharsets.UTF_8);
                for (String[] e : c.edits) {
                    int first = text.indexOf(e[0]);
                    if (first < 0) {
                        throw new IllegalStateException("EDIT anchor not found in " + c.path + ": " + abbreviate(e[0]));
                    }
                    if (text.indexOf(e[0], first + 1) >= 0) {
                        throw new IllegalStateException("EDIT anchor is ambiguous in " + c.path + ": " + abbreviate(e[0]));
                    }
                    text = text.substring(0, first) + e[1] + text.substring(first + e[0].length());
                }
                Files.writeString(target, text, StandardCharsets.UTF_8);
            }
            case DELETE -> Files.deleteIfExists(target);
            default -> throw new IllegalStateException("unknown op");
        }
    }

    private void restore(List<Map<String, Object>> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map<String, Object> e = entries.get(i);
            Path p = root.resolve(Json.str(e, "path")).normalize();
            try {
                if (Json.bool(e, "existed", false)) {
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, Json.str(e, "before", ""), StandardCharsets.UTF_8);
                } else {
                    Files.deleteIfExists(p);
                }
            } catch (IOException ex) {
                throw new UncheckedIOException("restore failed for " + p, ex);
            }
        }
    }

    private void writeJournal(String id, List<Map<String, Object>> entries) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("id", id);
        j.put("rolledBack", false);
        j.put("entries", entries);
        try {
            Files.createDirectories(journalDir);
            Files.writeString(journalDir.resolve(id + ".json"), Json.pretty(j), StandardCharsets.UTF_8);
        } catch (IOException e) {
            restore(entries);
            throw new UncheckedIOException("cannot write journal; changes reverted", e);
        }
    }

    private static String abbreviate(String s) {
        String one = s.replace("\n", "\\n");
        return one.length() > 80 ? one.substring(0, 80) + "…" : one;
    }

    /** Copies a source tree into the workspace (used to set up brownfield workspaces). */
    public static void copyTree(Path from, Path to) {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                String rel = from.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("target") || rel.contains("/target/") || rel.startsWith(".git")) {
                    continue;
                }
                Path dest = to.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
