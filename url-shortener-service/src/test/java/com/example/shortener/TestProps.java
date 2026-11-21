package com.example.shortener;

import com.example.shortener.config.ShortenerProperties;

import java.time.Duration;
import java.util.List;

/** Builds a {@link ShortenerProperties} for plain unit tests (no Spring context). */
public final class TestProps {

    private TestProps() {
    }

    public static ShortenerProperties withQueue(int queueCapacity, int batchSize) {
        return new ShortenerProperties(
                "http://sho.rt",
                "0x1234",
                new ShortenerProperties.Security(List.of("k1")),
                new ShortenerProperties.Validation(false),
                new ShortenerProperties.Cache(1000, Duration.ofMinutes(10)),
                new ShortenerProperties.RateLimit(10, 1, 10, 1),
                new ShortenerProperties.Analytics(queueCapacity, batchSize, 1000, "salt"),
                new ShortenerProperties.Idempotency(Duration.ofHours(24)));
    }

    public static ShortenerProperties defaults() {
        return withQueue(100, 10);
    }
}
