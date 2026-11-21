package com.example.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/** Stored response of a create call, keyed by (API key id, Idempotency-Key). */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    @Id
    @Column(name = "record_key", length = 160)
    private String recordKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_body", nullable = false, length = 4000)
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
        // for JPA
    }

    public IdempotencyRecord(String recordKey, String requestHash, String responseBody, Instant createdAt) {
        this.recordKey = recordKey;
        this.requestHash = requestHash;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }

    public boolean isExpired(Instant now, Duration ttl) {
        return createdAt.plus(ttl).isBefore(now);
    }

    public String getRecordKey() {
        return recordKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
