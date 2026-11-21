package com.example.sdlc.engine;

import com.example.sdlc.agents.AgentResult;
import com.example.sdlc.gates.Gates.GateResult;
import com.example.sdlc.model.FileChange;
import com.example.sdlc.policy.PolicyFinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a worker hands back to the coordinator after one attempt. Workers never mutate run state; only the
 * coordinator thread applies outcomes, so "stage finished" and "plan changed" can never race.
 */
final class Outcome {

    enum Kind { SUCCESS, NEEDS_APPROVAL, CLARIFY, FAILED, FATAL }

    final String stageId;
    Kind kind;
    AgentResult agent;
    Map<String, String> outputs = new LinkedHashMap<>();
    Map<String, Integer> inputVersions = new LinkedHashMap<>();
    List<FileChange> changes = new ArrayList<>();
    String journal;
    List<GateResult> gates = new ArrayList<>();
    List<PolicyFinding> findings = new ArrayList<>();
    List<String> feedback = new ArrayList<>();
    boolean retryable = true;
    boolean rolledBack;
    String error;
    String mode = "deterministic";
    long startedMs;
    long endedMs;

    Outcome(String stageId) {
        this.stageId = stageId;
    }

    Outcome fail(String error, boolean retryable) {
        this.kind = Kind.FAILED;
        this.error = error;
        this.retryable = retryable;
        this.feedback.add(error);
        return this;
    }
}
