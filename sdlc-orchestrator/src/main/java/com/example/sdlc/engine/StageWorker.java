package com.example.sdlc.engine;

import com.example.sdlc.agents.Agent;
import com.example.sdlc.agents.AgentContext;
import com.example.sdlc.agents.AgentResult;
import com.example.sdlc.gates.Gates;
import com.example.sdlc.gates.Gates.GateContext;
import com.example.sdlc.gates.Gates.GateResult;
import com.example.sdlc.json.Json;
import com.example.sdlc.model.FileChange;
import com.example.sdlc.model.StageDef;
import com.example.sdlc.policy.Effect;
import com.example.sdlc.policy.PolicyEngine;
import com.example.sdlc.policy.PolicyFinding;
import com.example.sdlc.workspace.BoundaryViolation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes one attempt of one stage on a worker thread:
 * <pre>
 * entry gates → agent (or human-approved change set) → policy review → apply with journal → exit gates
 *                                                                        └─ any exit gate fails → roll back the journal
 * </pre>
 * Returns an {@link Outcome}. It never touches run state, so the coordinator stays the single writer.
 */
final class StageWorker {

    private final Gates gates;
    private final PolicyEngine policy;

    StageWorker(Gates gates, PolicyEngine policy) {
        this.gates = gates;
        this.policy = policy;
    }

    Outcome execute(Agent agent, AgentContext ctx, String journalId, Map<String, Object> approvedProposal, long nowMs) {
        StageDef stage = ctx.stage();
        Outcome o = new Outcome(stage.id);
        o.startedMs = nowMs;
        o.inputVersions.putAll(ctx.inputVersions());
        try {
            for (String g : stage.entryGates) {
                GateResult r = gates.run(g, new GateContext(stage, ctx.inputs(), Map.of(), ctx.workspace(), ctx.config(), ctx.testRunner()));
                o.gates.add(r);
                if (!r.passed()) {
                    return done(o.fail("entry " + r.feedback(), false));
                }
            }

            AgentResult result;
            List<FileChange> changes;
            if (approvedProposal != null) {
                result = new AgentResult();
                result.mode = "human-approved";
                Json.obj(approvedProposal.get("outputs")).forEach((k, v) -> result.outputs.put(k, String.valueOf(v)));
                changes = FileChange.listFromJson(approvedProposal.get("changes"));
            } else {
                result = agent.run(ctx);
                changes = result.changes;
                if (!result.questions.isEmpty()) {
                    o.kind = Outcome.Kind.CLARIFY;
                    o.agent = result;
                    return done(o);
                }
                if (!changes.isEmpty()) {
                    List<PolicyFinding> findings = policy.evaluateChanges(changes, stage.writeScope, ctx.workspace());
                    o.findings.addAll(findings);
                    if (PolicyEngine.hasCritical(findings)) {
                        o.kind = Outcome.Kind.FATAL;
                        o.agent = result;
                        o.error = "critical policy violation: " + findings.stream().filter(PolicyFinding::critical)
                                .map(PolicyFinding::summary).toList();
                        return done(o);
                    }
                    Effect effect = PolicyEngine.strictest(findings);
                    if (effect == Effect.DENY) {
                        o.agent = result;
                        o.fail("policy denied the change set", true);
                        findings.stream().filter(f -> f.effect() == Effect.DENY).forEach(f -> o.feedback.add(f.summary()));
                        return done(o);
                    }
                    if (effect == Effect.REQUIRE_APPROVAL) {
                        o.kind = Outcome.Kind.NEEDS_APPROVAL;
                        o.agent = result;
                        o.changes = changes;
                        o.outputs.putAll(result.outputs);
                        o.mode = result.mode;
                        return done(o);
                    }
                }
            }
            o.agent = result;
            o.mode = result.mode;
            o.changes = changes;

            if (!changes.isEmpty()) {
                o.journal = ctx.workspace().apply(journalId, changes, stage.writeScope);
            }
            Map<String, String> outputs = new LinkedHashMap<>(result.outputs);
            String changeArtifact = changeArtifact(stage);
            if (changeArtifact != null) {
                outputs.put(changeArtifact, Json.pretty(Map.of("changes", changes)));
            }
            o.outputs = outputs;

            for (String g : stage.exitGates) {
                GateResult r = gates.run(g, new GateContext(stage, ctx.inputs(), outputs, ctx.workspace(), ctx.config(), ctx.testRunner()));
                o.gates.add(r);
                if (!r.passed()) {
                    if (o.journal != null) {
                        ctx.workspace().rollback(o.journal);
                        o.rolledBack = true;
                    }
                    o.fail(r.feedback(), true);
                    return done(o);
                }
            }
            o.kind = Outcome.Kind.SUCCESS;
            return done(o);
        } catch (BoundaryViolation e) {
            o.kind = Outcome.Kind.FATAL;
            o.error = "critical: " + e.getMessage();
            return done(o);
        } catch (Exception e) {
            if (o.journal != null && !o.rolledBack) {
                ctx.workspace().rollback(o.journal);
                o.rolledBack = true;
            }
            return done(o.fail("attempt error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), true));
        }
    }

    static String changeArtifact(StageDef stage) {
        if (!stage.mutatesWorkspace) {
            return null;
        }
        return stage.produces.stream().filter(p -> p.endsWith("-changes")).findFirst().orElse(stage.id + "-changes");
    }

    private static Outcome done(Outcome o) {
        o.endedMs = System.currentTimeMillis();
        return o;
    }
}
