package com.example.shortener.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory token-bucket rate limiter keyed by client (API key id or IP hash).
 *
 * <p>Trade-off: state is per instance. With N replicas the effective limit is N times higher.
 * The production path is a shared store (e.g. Redis + Lua script). This is documented in the limitations.
 */
public class TokenBucketRateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(long capacity, double refillPerSecond, LongSupplier nanoClock) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity and refill rate must be positive");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / NANOS_PER_SECOND;
        this.nanoClock = nanoClock;
    }

    public TokenBucketRateLimiter(long capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    /** Tries to take one token. */
    public Decision tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, nanoClock.getAsLong()));
        return bucket.tryTake(nanoClock.getAsLong());
    }

    /** Removes buckets idle for longer than {@code idle}, which keeps memory bounded. */
    public int evictIdle(Duration idle) {
        long cutoff = nanoClock.getAsLong() - idle.toNanos();
        int before = buckets.size();
        buckets.entrySet().removeIf(e -> e.getValue().lastSeen() < cutoff);
        return before - buckets.size();
    }

    int trackedKeys() {
        return buckets.size();
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private final class Bucket {
        private double tokens;
        private long lastRefill;
        private volatile long lastSeen;

        Bucket(long tokens, long now) {
            this.tokens = tokens;
            this.lastRefill = now;
            this.lastSeen = now;
        }

        synchronized Decision tryTake(long now) {
            lastSeen = now;
            long elapsed = Math.max(0, now - lastRefill);
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefill = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Decision(true, 0);
            }
            double missing = 1.0 - tokens;
            long waitSeconds = (long) Math.ceil(missing / refillPerNano / NANOS_PER_SECOND);
            return new Decision(false, Math.max(1, waitSeconds));
        }

        long lastSeen() {
            return lastSeen;
        }
    }
}
