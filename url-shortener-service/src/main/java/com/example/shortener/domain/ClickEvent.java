package com.example.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** One redirect. Holds only coarse, privacy-safe attributes. */
@Entity
@Table(name = "click_event")
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "click_event_seq")
    @SequenceGenerator(name = "click_event_seq", sequenceName = "click_event_seq", allocationSize = 1)
    private Long id;

    @Column(name = "short_url_id", nullable = false)
    private Long shortUrlId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** UTC day, stored so daily stats are a simple indexed GROUP BY. */
    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Column(name = "referrer_host", nullable = false, length = 255)
    private String referrerHost;

    @Column(name = "ua_family", nullable = false, length = 32)
    private String uaFamily;

    @Column(name = "ip_hash", nullable = false, length = 64)
    private String ipHash;

    protected ClickEvent() {
        // for JPA
    }

    public ClickEvent(Long shortUrlId, Instant occurredAt, LocalDate occurredOn,
                      String referrerHost, String uaFamily, String ipHash) {
        this.shortUrlId = shortUrlId;
        this.occurredAt = occurredAt;
        this.occurredOn = occurredOn;
        this.referrerHost = referrerHost;
        this.uaFamily = uaFamily;
        this.ipHash = ipHash;
    }

    public Long getId() {
        return id;
    }

    public Long getShortUrlId() {
        return shortUrlId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public String getReferrerHost() {
        return referrerHost;
    }

    public String getUaFamily() {
        return uaFamily;
    }

    public String getIpHash() {
        return ipHash;
    }
}
