package com.example.sdlc;

import com.example.sdlc.approval.ApprovalService;
import com.example.sdlc.approval.ApproverRegistry;
import com.example.sdlc.model.ApprovalRequest;
import com.example.sdlc.model.FileChange;
import com.example.sdlc.model.Risk;
import com.example.sdlc.model.RunState;
import com.example.sdlc.policy.Effect;
import com.example.sdlc.policy.PolicyEngine;
import com.example.sdlc.policy.PolicyFinding;
import com.example.sdlc.workspace.GuardedWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Policy guardrails and human-approval rules. */
class GovernanceTest {

    private final PolicyEngine policy = PolicyEngine.defaults();
    private GuardedWorkspace ws;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Files.createTempDirectory("gov");
        Files.createDirectories(root.resolve("src/main/resources/db/migration"));
        Files.writeString(root.resolve("src/main/resources/db/migration/V1__init.sql"), "create table t(id int);");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        ws = new GuardedWorkspace(root, root.resolve(".journals"));
    }

    private List<PolicyFinding> eval(FileChange... changes) {
        return policy.evaluateChanges(List.of(changes), List.of("**"), ws);
    }

    @Test
    void hardCodedSecretIsACriticalDeny() {
        List<PolicyFinding> f = eval(FileChange.create("src/main/java/Cfg.java",
                "String apiKey = \"AKIAABCDEFGHIJKLMNOP\";"));
        assertTrue(PolicyEngine.hasCritical(f));
        assertEquals("SEC-001", f.get(0).ruleId());
    }

    @Test
    void writingOutsideScopeIsCritical() {
        List<PolicyFinding> f = policy.evaluateChanges(List.of(FileChange.create("src/test/X.java", "x")),
                List.of("src/main/**"), ws);
        assertTrue(PolicyEngine.hasCritical(f));
        assertEquals("SEC-004", f.get(0).ruleId());
    }

    @Test
    void injectionAndProcessExecutionAreDenied() {
        List<PolicyFinding> f = eval(FileChange.create("src/main/java/Repo.java",
                "em.createNativeQuery(\"select * from t where id = \" + id); new ProcessBuilder(\"sh\");"));
        assertEquals(Effect.DENY, PolicyEngine.strictest(f));
        assertFalse(PolicyEngine.hasCritical(f), "non-critical: fed back to the agent for a retry");
        assertEquals(2, f.stream().filter(x -> x.ruleId().equals("SEC-002")).count());
    }

    @Test
    void newMigrationNeedsTechLeadButEditingOneIsDenied() {
        List<PolicyFinding> add = eval(FileChange.create("src/main/resources/db/migration/V2__x.sql",
                "ALTER TABLE t ADD COLUMN c INT; -- rollback: ignore column"));
        assertEquals(Effect.REQUIRE_APPROVAL, PolicyEngine.strictest(add));
        assertEquals(List.of("tech-lead"), PolicyEngine.requiredRoles(add));

        List<PolicyFinding> edit = eval(FileChange.edit("src/main/resources/db/migration/V1__init.sql", "int", "bigint"));
        assertEquals(Effect.DENY, PolicyEngine.strictest(edit));
    }

    @Test
    void destructiveDdlIsDenied() {
        List<PolicyFinding> f = eval(FileChange.create("src/main/resources/db/migration/V2__drop.sql", "ALTER TABLE t DROP COLUMN c;"));
        assertTrue(f.stream().anyMatch(x -> x.ruleId().equals("CHG-006") && x.effect() == Effect.DENY));
    }

    @Test
    void dependencyChangesAreControlled() {
        FileChange allowed = FileChange.edit("pom.xml", "<project/>", "<project><groupId>org.springframework.boot</groupId></project>");
        assertEquals(Effect.REQUIRE_APPROVAL, PolicyEngine.strictest(eval(allowed)));
        FileChange unknown = FileChange.edit("pom.xml", "<project/>", "<project><groupId>com.evil</groupId></project>");
        assertEquals(Effect.DENY, PolicyEngine.strictest(eval(unknown)));
    }

    @Test
    void largeChangesNeedReview() {
        String big = "line\n".repeat(500);
        List<PolicyFinding> f = eval(FileChange.create("src/main/java/Big.java", big));
        assertTrue(f.stream().anyMatch(x -> x.ruleId().equals("CHG-004")));
    }

    @Test
    void complianceBlocksRawPiiButAllowsPseudonymisedForms() {
        assertEquals(1, policy.evaluateText("spec", "Store the raw IP address of every visitor.").size());
        assertEquals(1, policy.evaluateText("spec", "We should log user emails for support.").size());
        assertEquals(0, policy.evaluateText("spec", "Visitor IP addresses are pseudonymised with a salted HMAC.").size());
        assertEquals(0, policy.evaluateText("spec", "Do not store raw IP addresses.").size());
        assertEquals(0, policy.evaluateText("spec", "Track click counts per day.").size());
    }

    // ---------------------------------------------------------------- approvals

    private RunState runWith(ApprovalRequest a) {
        RunState run = new RunState();
        run.runId = "run-x";
        run.initiator = "manas";
        run.approvals.add(a);
        return run;
    }

    private static ApprovalRequest request(Risk risk, String role) {
        ApprovalRequest a = new ApprovalRequest();
        a.id = "A-1";
        a.stageId = "implement";
        a.kind = ApprovalRequest.Kind.CHANGE_SET;
        a.risk = risk;
        a.requiredRole = role;
        return a;
    }

    private final ApprovalService approvals = new ApprovalService(new ApproverRegistry(Map.of(
            "manas", List.of("engineer", "tech-lead"),
            "alice", List.of("tech-lead"),
            "priya", List.of("security"))), Clock.systemUTC());

    @Test
    void separationOfDutiesBlocksSelfApproval() {
        RunState run = runWith(request(Risk.MEDIUM, "tech-lead"));
        ApprovalService.ApprovalException e = assertThrows(ApprovalService.ApprovalException.class,
                () -> approvals.decide(run, "A-1", "manas", true, null));
        assertTrue(e.getMessage().contains("separation of duties"));
        assertEquals(ApprovalRequest.Status.APPROVED, approvals.decide(run, "A-1", "alice", true, "lgtm").status);
    }

    @Test
    void approverNeedsTheRequiredRole() {
        RunState run = runWith(request(Risk.MEDIUM, "security"));
        assertThrows(ApprovalService.ApprovalException.class, () -> approvals.decide(run, "A-1", "alice", true, null));
        assertThrows(ApprovalService.ApprovalException.class, () -> approvals.decide(run, "A-1", "mallory", true, null));
        approvals.decide(run, "A-1", "priya", true, null);
        assertThrows(ApprovalService.ApprovalException.class, () -> approvals.decide(run, "A-1", "priya", true, null),
                "a decision cannot be changed once made");
    }

    @Test
    void rejectionRequiresAComment() {
        RunState run = runWith(request(Risk.HIGH, "tech-lead"));
        assertThrows(ApprovalService.ApprovalException.class, () -> approvals.decide(run, "A-1", "alice", false, " "));
        ApprovalRequest r = approvals.decide(run, "A-1", "alice", false, "split the migration");
        assertEquals(ApprovalRequest.Status.REJECTED, r.status);
        assertEquals("tech-lead", r.decidedRole);
    }
}
