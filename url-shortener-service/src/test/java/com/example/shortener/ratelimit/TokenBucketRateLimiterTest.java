package com.example.shortener.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;
    private final AtomicLong now = new AtomicLong(0);
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 1.0, now::get);

    @Test
    void allowsBurstUpToCapacityThenLimits() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        }
        TokenBucketRateLimiter.Decision denied = limiter.tryAcquire("k");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsOverTimeButNeverAboveCapacity() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k");
        }
        now.addAndGet(SECOND);
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();

        now.addAndGet(100 * SECOND); // long idle: still capped at 3
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire("k").allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void evictsIdleBuckets() {
        limiter.tryAcquire("old");
        now.addAndGet(Duration.ofMinutes(20).toNanos());
        limiter.tryAcquire("fresh");
        assertThat(limiter.evictIdle(Duration.ofMinutes(10))).isEqualTo(1);
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void neverGrantsMoreThanCapacityUnderConcurrency() throws InterruptedException {
        TokenBucketRateLimiter frozen = new TokenBucketRateLimiter(50, 0.0001, () -> 0L);
        AtomicInteger granted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 1_000; i++) {
            pool.submit(() -> {
                if (frozen.tryAcquire("shared").allowed()) {
                    granted.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(granted.get()).isEqualTo(50);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketRateLimiter(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
