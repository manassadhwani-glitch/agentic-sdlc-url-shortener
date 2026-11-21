package com.example.sdlc.audit;

/** The audit trail could not be written. The engine treats this as fatal (fail-closed). */
public class AuditException extends RuntimeException {
    public AuditException(String message, Throwable cause) {
        super(message, cause);
    }
}
