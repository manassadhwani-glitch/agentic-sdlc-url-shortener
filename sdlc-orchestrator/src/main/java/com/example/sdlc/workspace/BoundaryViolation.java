package com.example.sdlc.workspace;

/** An agent tried to write outside its allowed area. Treated as a CRITICAL policy violation (no retry). */
public class BoundaryViolation extends RuntimeException {
    public BoundaryViolation(String message) {
        super(message);
    }
}
