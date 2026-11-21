package com.example.sdlc.audit;

import com.example.sdlc.json.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only, HMAC-chained audit log (JSON Lines).
 *
 * <p>Each entry stores {@code hash = HMAC(key, prevHash | entry-without-hash)}. Editing, deleting or reordering any
 * line breaks the chain from that point on. Because the hash is keyed (HMAC, not plain SHA-256), someone with write
 * access to the file but without the key cannot recompute a valid chain after tampering. A plain hash chain only
 * detects accidental corruption.
 *
 * <p><b>Fail-closed:</b> if an entry cannot be written, {@link AuditException} is thrown and the engine safe-stops.
 * No action happens without an audit record.
 */
public final class AuditLog {

    public static final String KEY_ENV = "ORCH_AUDIT_KEY";
    static final String DEV_KEY = "dev-only-audit-key-change-me";
    private static final String GENESIS = "0".repeat(64);

    private final Path file;
    private final String runId;
    private final SecretKeySpec key;
    private final Clock clock;
    private final boolean devKey;
    private long seq;
    private String lastHash = GENESIS;

    public AuditLog(Path file, String runId, String secret, Clock clock) {
        this.file = file;
        this.runId = runId;
        this.devKey = secret == null || secret.isBlank();
        String k = devKey ? DEV_KEY : secret;
        this.key = new SecretKeySpec(k.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.clock = clock;
        recoverTail();
    }

    public static AuditLog forRun(Path runDir, String runId, Clock clock) {
        return new AuditLog(runDir.resolve("audit.jsonl"), runId, System.getenv(KEY_ENV), clock);
    }

    public boolean usingDevKey() {
        return devKey;
    }

    /** Appends an event. Thread-safe. */
    public synchronized Map<String, Object> append(String type, String actor, String stage, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("seq", ++seq);
        e.put("ts", clock.instant().toString());
        e.put("runId", runId);
        e.put("traceId", runId);
        e.put("spanId", stage == null ? "run" : stage);
        e.put("type", type);
        e.put("actor", actor);
        e.put("stage", stage);
        e.put("data", data == null ? Map.of() : data);
        e.put("prev", lastHash);
        String hash = hmac(lastHash + "|" + Json.write(e));
        e.put("hash", hash);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(e) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException ex) {
            seq--;
            throw new AuditException("audit write failed: " + ex.getMessage(), ex);
        }
        lastHash = hash;
        return e;
    }

    /** Recomputes the whole chain. */
    public VerifyResult verify() {
        if (!Files.exists(file)) {
            return new VerifyResult(true, 0, -1, "no audit log yet");
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new VerifyResult(false, 0, -1, "cannot read audit log: " + e.getMessage());
        }
        String prev = GENESIS;
        long expectedSeq = 1;
        int count = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> e;
            try {
                e = Json.parseObject(line);
            } catch (RuntimeException ex) {
                return new VerifyResult(false, count, expectedSeq, "unparseable entry");
            }
            long s = Json.num(e, "seq", -1);
            String stored = (String) e.remove("hash");
            if (s != expectedSeq) {
                return new VerifyResult(false, count, s, "sequence gap or reorder at seq " + s);
            }
            if (!prev.equals(e.get("prev"))) {
                return new VerifyResult(false, count, s, "broken link at seq " + s);
            }
            String recomputed = hmac(prev + "|" + Json.write(e));
            if (!recomputed.equals(stored)) {
                return new VerifyResult(false, count, s, "hash mismatch (tampered) at seq " + s);
            }
            prev = stored;
            expectedSeq++;
            count++;
        }
        return new VerifyResult(true, count, -1, "chain intact (" + count + " entries)");
    }

    public List<Map<String, Object>> readAll() {
        try {
            if (!Files.exists(file)) {
                return List.of();
            }
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank()).map(Json::parseObject).toList();
        } catch (IOException e) {
            throw new AuditException("cannot read audit log", e);
        }
    }

    private void recoverTail() {
        List<Map<String, Object>> all = readAll();
        if (!all.isEmpty()) {
            Map<String, Object> last = all.get(all.size() - 1);
            seq = Json.num(last, "seq", 0);
            lastHash = Json.str(last, "hash");
        }
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public record VerifyResult(boolean ok, int entries, long badSeq, String message) {
    }
}
