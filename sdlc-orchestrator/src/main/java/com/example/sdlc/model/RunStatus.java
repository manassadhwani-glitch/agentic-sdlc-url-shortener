package com.example.sdlc.model;

public enum RunStatus {
    RUNNING,
    /** Waiting for a human (approval or clarification). State is on disk; `resume` continues. */
    PAUSED,
    SUCCEEDED,
    /** Terminal failure: completed work was compensated (rolled back). */
    FAILED,
    /** Safe-stopped (kill switch, budget, critical policy). Completed work kept; resumable. */
    STOPPED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
