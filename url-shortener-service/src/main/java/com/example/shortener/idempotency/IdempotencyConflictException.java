package com.example.shortener.idempotency;

/** The same Idempotency-Key was reused with a different request body. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency-Key was already used with a different request body");
    }
}
