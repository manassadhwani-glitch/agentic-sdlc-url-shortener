package com.example.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "short_url")
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "short_url_seq")
    @SequenceGenerator(name = "short_url_seq", sequenceName = "short_url_seq", allocationSize = 1)
    private Long id;

    @Column(name = "short_code", unique = true, length = 64)
    private String shortCode;

    @Column(name = "long_url", nullable = false, length = 2048)
    private String longUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LinkStatus status;

    @Column(name = "custom_alias", nullable = false)
    private boolean customAlias;

    @Column(name = "owner_key_id", nullable = false, length = 16)
    private String ownerKeyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ShortUrl() {
        // for JPA
    }

    private ShortUrl(String longUrl, String shortCode, boolean customAlias, String ownerKeyId, Instant createdAt) {
        this.longUrl = longUrl;
        this.shortCode = shortCode;
        this.customAlias = customAlias;
        this.ownerKeyId = ownerKeyId;
        this.createdAt = createdAt;
        this.status = LinkStatus.ACTIVE;
    }

    /** A link whose code will be generated from its id after the insert. */
    public static ShortUrl generated(String longUrl, String ownerKeyId, Instant now) {
        return new ShortUrl(longUrl, null, false, ownerKeyId, now);
    }

    public static ShortUrl withAlias(String longUrl, String alias, String ownerKeyId, Instant now) {
        return new ShortUrl(longUrl, alias, true, ownerKeyId, now);
    }

    public void assignShortCode(String code) {
        if (this.shortCode != null) {
            throw new IllegalStateException("short code already assigned");
        }
        this.shortCode = code;
    }

    public void disable() {
        this.status = LinkStatus.DISABLED;
    }

    public boolean isOwnedBy(String keyId) {
        return ownerKeyId.equals(keyId);
    }

    public Long getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public LinkStatus getStatus() {
        return status;
    }

    public boolean isCustomAlias() {
        return customAlias;
    }

    public String getOwnerKeyId() {
        return ownerKeyId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
