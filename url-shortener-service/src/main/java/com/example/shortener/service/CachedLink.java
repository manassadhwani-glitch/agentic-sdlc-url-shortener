package com.example.shortener.service;

import com.example.shortener.domain.LinkStatus;
import com.example.shortener.domain.ShortUrl;

/** Immutable, cache-friendly view of a link: only what the redirect path needs. */
public record CachedLink(long id, String longUrl, LinkStatus status) {

    public static CachedLink from(ShortUrl link) {
        return new CachedLink(link.getId(), link.getLongUrl(), link.getStatus());
    }
}
