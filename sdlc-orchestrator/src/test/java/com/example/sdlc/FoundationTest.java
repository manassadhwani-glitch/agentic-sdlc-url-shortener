package com.example.sdlc;

import com.example.sdlc.audit.AuditLog;
import com.example.sdlc.json.Json;
import com.example.sdlc.model.FileChange;
import com.example.sdlc.model.StageDef;
import com.example.sdlc.workflow.WorkflowLoader;
import com.example.sdlc.workflow.WorkflowValidator;
import com.example.sdlc.workspace.BoundaryViolation;
import com.example.sdlc.workspace.GuardedWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON, audit chain, workflow validation and guarded workspace. */
class FoundationTest {

    private Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        tmp = Files.createTempDirectory("orch-test");
    }

    // ---------------------------------------------------------------- JSON

    @Test
    void jsonRoundTripsAndEscapes() {
        String text = "{\"a\":\"line\\nbreak \\\"q\\\" \\u00e9\",\"n\":42,\"d\":1.5,\"b\":true,\"z\":null,\"l\":[1,{\"x\":\"y\"}]}";
        Map<String, Object> m = Json.parseObject(text);
        assertEquals("line\nbreak \"q\" é", m.get("a"));
        assertEquals(42L, m.get("n"));
        assertEquals(1.5, m.get("d"));
        assertNull(m.get("z"));
        assertEquals(m, Json.parseObject(Json.write(m)));
        assertEquals(m, Json.parseObject(Json.pretty(m)));
    }

    @Test
    void jsonExtractsObjectFromProse() {
        Map<String, Object> m = Json.extractObject("Sure! Here you go:\n```json\n{\"features\": [\"expiry\"], \"x\": \"}\"}\n```");
        assertEquals(List.of("expiry"), m.get("features"));
        assertNull(Json.extractObject("no json here"));
    }

    @Test
    void jsonRejectsMalformedInput() {
        assertThrows(RuntimeException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(RuntimeException.class, () -> Json.parse("[1, 2"));
        assertThrows(RuntimeException.class, () -> Json.parse("{} trailing"));
    }

    // ---------------------------------------------------------------- audit

    @Test
    void auditChainVerifiesAndDetectsTampering() throws Exception {
        Path file = tmp.resolve("audit.jsonl");
        AuditLog log = new AuditLog(file, "run-1", "secret-key", Clock.systemUTC());
        log.append("run.started", "manas", null, Map.of("k", "v"));
        log.append("gate", "system", "design", Map.of("passed", true));
        log.append("approval.granted", "human:alice", "design", Map.of("comment", "ok"));
        assertTrue(log.verify().ok());
        assertEquals(3, log.verify().entries());

        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        lines.set(1, lines.get(1).replace("\"passed\":true", "\"passed\":false"));
        Files.write(file, lines);
        AuditLog.VerifyResult r = new AuditLog(file, "run-1", "secret-key", Clock.systemUTC()).verify();
        assertFalse(r.ok());
        assertEquals(2L, r.badSeq());
    }

    @Test
    void auditChainDetectsDeletionAndWrongKey() throws Exception {
        Path file = tmp.resolve("audit.jsonl");
        AuditLog log = new AuditLog(file, "run-1", "secret-key", Clock.systemUTC());
        for (int i = 0; i < 4; i++) {
            log.append("e" + i, "system", null, Map.of());
        }
        assertFalse(new AuditLog(file, "run-1", "attacker-key", Clock.systemUTC()).verify().ok(),
                "a chain recomputed without the key must not verify");
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        lines.remove(2);
        Files.write(file, lines);
        assertFalse(new AuditLog(file, "run-1", "secret-key", Clock.systemUTC()).verify().ok());
    }

    @Test
    void auditResumesChainAcrossInstances() {
        Path file = tmp.resolve("audit.jsonl");
        new AuditLog(file, "run-1", "k", Clock.systemUTC()).append("a", "x", null, Map.of());
        AuditLog second = new AuditLog(file, "run-1", "k", Clock.systemUTC());
        second.append("b", "x", null, Map.of());
        assertTrue(second.verify().ok());
        assertEquals(2, second.verify().entries());
    }

    // ---------------------------------------------------------------- workflow validation

    private final WorkflowValidator validator = new WorkflowValidator(
            Set.of("requirements", "design", "planner", "implementation", "test-design", "test-runner",
                    "security-review", "docs", "release", "migration-check", "fake"),
            Set.of("required-outputs", "spec-ready", "spec-complete", "compliance-text", "design-covers-acs",
                    "plan-valid", "build", "tests-cover-acs", "tests-pass", "ac-traceability", "no-high-findings",
                    "release-checklist", "migration-safe"));

    @Test
    void defaultWorkflowIsValidWithParallelWaves() {
        List<StageDef> stages = WorkflowLoader.load("sdlc-default");
        assertEquals(List.of(), validator.validate(stages));
        List<List<String>> waves = WorkflowValidator.waves(stages);
        assertTrue(waves.contains(List.of("implement", "test-design")), "implement ‖ test-design: " + waves);
        assertTrue(waves.contains(List.of("security", "docs")), "security ‖ docs: " + waves);
    }

    @Test
    void validatorRejectsCyclesUnknownDepsAndUnsafeStages() {
        StageDef a = stage("a", "b");
        StageDef b = stage("b", "a");
        assertTrue(validator.validate(List.of(a, b)).stream().anyMatch(e -> e.contains("cycle")));

        assertTrue(validator.validate(List.of(stage("x", "missing"))).stream().anyMatch(e -> e.contains("unknown stage")));

        StageDef mut = stage("m");
        mut.mutatesWorkspace = true;
        assertTrue(validator.validate(List.of(mut)).stream().anyMatch(e -> e.contains("writeScope")));

        StageDef retry = stage("r");
        retry.maxAttempts = 50;
        assertTrue(validator.validate(List.of(retry)).stream().anyMatch(e -> e.contains("bounded")));

        StageDef signoff = stage("s");
        signoff.approval = "always";
        assertTrue(validator.validate(List.of(signoff)).stream().anyMatch(e -> e.contains("approvalRole")));

        StageDef down = stage("down", "up");
        down.onReject = "elsewhere";
        assertTrue(validator.validate(List.of(stage("up"), down, stage("elsewhere"))).stream()
                .anyMatch(e -> e.contains("onReject")));
    }

    static StageDef stage(String id, String... deps) {
        StageDef s = new StageDef();
        s.id = id;
        s.agent = "fake";
        s.dependsOn = new ArrayList<>(List.of(deps));
        return s;
    }

    // ---------------------------------------------------------------- guarded workspace

    @Test
    void workspaceAppliesAndRollsBackExactly() throws Exception {
        GuardedWorkspace ws = new GuardedWorkspace(tmp.resolve("ws"), tmp.resolve("journals"));
        Files.createDirectories(tmp.resolve("ws/src/main"));
        Files.writeString(tmp.resolve("ws/src/main/A.java"), "class A { int x = 1; }", StandardCharsets.UTF_8);

        ws.apply("j1", List.of(FileChange.edit("src/main/A.java", "int x = 1;", "int x = 2;"),
                FileChange.create("src/main/B.java", "class B {}")), List.of("src/main/**"));
        assertEquals("class A { int x = 2; }", ws.read("src/main/A.java"));
        assertTrue(ws.exists("src/main/B.java"));

        assertEquals(2, ws.rollback("j1"));
        assertEquals("class A { int x = 1; }", ws.read("src/main/A.java"));
        assertFalse(ws.exists("src/main/B.java"));
        assertEquals(0, ws.rollback("j1"), "rollback is idempotent");
    }

    @Test
    void workspaceEnforcesBoundaries() {
        GuardedWorkspace ws = new GuardedWorkspace(tmp.resolve("ws"), tmp.resolve("journals"));
        List<String> scope = List.of("src/main/**");
        assertThrows(BoundaryViolation.class, () -> ws.apply("j", List.of(FileChange.create("../escape.txt", "x")), scope));
        assertThrows(BoundaryViolation.class, () -> ws.apply("j", List.of(FileChange.create("/etc/passwd", "x")), scope));
        assertThrows(BoundaryViolation.class, () -> ws.apply("j", List.of(FileChange.create("src/test/T.java", "x")), scope));
        assertThrows(BoundaryViolation.class, () -> ws.apply("j", List.of(FileChange.create("src/main/../../x", "x")), scope));
    }

    @Test
    void failedChangeSetLeavesNoPartialWrites() throws Exception {
        GuardedWorkspace ws = new GuardedWorkspace(tmp.resolve("ws"), tmp.resolve("journals"));
        Files.createDirectories(tmp.resolve("ws/src/main"));
        Files.writeString(tmp.resolve("ws/src/main/A.java"), "a a", StandardCharsets.UTF_8);
        List<FileChange> changes = List.of(FileChange.create("src/main/New.java", "new"),
                FileChange.edit("src/main/A.java", "a", "b")); // ambiguous anchor → whole set must be undone
        assertThrows(IllegalStateException.class, () -> ws.apply("j", changes, List.of("src/main/**")));
        assertFalse(ws.exists("src/main/New.java"));
        assertEquals("a a", ws.read("src/main/A.java"));
    }
}
