package com.example.sdlc.model;

/**
 * Lifecycle of one stage in a run.
 *
 * <pre>
 * PENDING → READY → RUNNING → SUCCEEDED
 *                     ├→ RETRY_WAIT → (READY again, bounded, with backoff)
 *                     ├→ AWAITING_APPROVAL → SUCCEEDED | (rejected → rework)
 *                     ├→ AWAITING_CLARIFICATION → (answers → READY)
 *                     └→ FAILED
 * SUCCEEDED → PENDING  when an input it consumed changed (re-plan / invalidation)
 * any non-terminal → CANCELLED on safe-stop
 * </pre>
 */
public enum NodeState {
    PENDING, READY, RUNNING, RETRY_WAIT, AWAITING_APPROVAL, AWAITING_CLARIFICATION,
    SUCCEEDED, SKIPPED, FAILED, CANCELLED;

    public boolean isDone() {
        return this == SUCCEEDED || this == SKIPPED;
    }

    public boolean isWaitingForHuman() {
        return this == AWAITING_APPROVAL || this == AWAITING_CLARIFICATION;
    }
}
