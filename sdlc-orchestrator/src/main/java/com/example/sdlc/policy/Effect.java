package com.example.sdlc.policy;

/** Ordered by severity: the strictest effect across all findings wins. */
public enum Effect {
    ALLOW, REQUIRE_APPROVAL, DENY
}
