-- V1: core schema for the URL shortener. Portable across H2 (PostgreSQL mode) and PostgreSQL.

CREATE SEQUENCE short_url_seq START WITH 100000 INCREMENT BY 1;

CREATE TABLE short_url (
    id            BIGINT PRIMARY KEY,
    short_code    VARCHAR(64),
    long_url      VARCHAR(2048) NOT NULL,
    status        VARCHAR(16)   NOT NULL,
    custom_alias  BOOLEAN       NOT NULL,
    owner_key_id  VARCHAR(16)   NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_short_url_code UNIQUE (short_code)
);

CREATE SEQUENCE click_event_seq START WITH 1 INCREMENT BY 1;

-- Privacy: the client IP is never stored, only a salted HMAC (ip_hash).
CREATE TABLE click_event (
    id             BIGINT PRIMARY KEY,
    short_url_id   BIGINT       NOT NULL REFERENCES short_url (id),
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    occurred_on    DATE         NOT NULL,
    referrer_host  VARCHAR(255) NOT NULL,
    ua_family      VARCHAR(32)  NOT NULL,
    ip_hash        VARCHAR(64)  NOT NULL
);

CREATE INDEX idx_click_event_url_day ON click_event (short_url_id, occurred_on);

CREATE TABLE idempotency_record (
    record_key     VARCHAR(160)  PRIMARY KEY,
    request_hash   VARCHAR(64)   NOT NULL,
    response_body  VARCHAR(4000) NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_idempotency_created ON idempotency_record (created_at);
