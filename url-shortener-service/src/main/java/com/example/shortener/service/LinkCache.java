package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Read-through cache for the redirect hot path.
 *
 * <p>Only links that exist are cached; unknown codes are not cached (negative caching would let an attacker
 * fill the cache with junk keys). Unknown-code floods are handled by the per-IP rate limiter instead.
 * The TTL bounds how stale an entry can be on <i>other</i> instances after a link is disabled.
 */
@Component
public class LinkCache {

    private final Cache<String, CachedLink> cache;

    public LinkCache(ShortenerProperties props, MeterRegistry registry) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(props.cache().maxSize())
                .expireAfterWrite(props.cache().ttl())
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(registry, cache, "links");
    }

    /** Returns the cached value or loads it; a null from the loader is not cached. */
    public CachedLink get(String code, Function<String, CachedLink> loader) {
        return cache.get(code, loader);
    }

    public void invalidate(String code) {
        cache.invalidate(code);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
