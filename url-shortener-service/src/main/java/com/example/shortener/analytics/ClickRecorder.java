package com.example.shortener.analytics;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.ClickEvent;
import com.example.shortener.repository.ClickEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Records clicks off the redirect path.
 *
 * <p>The redirect thread only does a non-blocking {@code offer} onto a bounded queue. A scheduled flusher
 * batch-inserts the events. If the queue is full (database slow or down), the click is <b>dropped and counted</b>:
 * we choose redirect availability over analytics completeness.
 *
 * <p>Known limitation: events still in the queue are lost if the process crashes. A durable log (Kafka) is the
 * production path.
 */
@Component
public class ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);

    private final BlockingQueue<ClickEvent> queue;
    private final ClickEventRepository repository;
    private final IpHasher ipHasher;
    private final Clock clock;
    private final int batchSize;
    private final Counter accepted;
    private final Counter dropped;
    private final Counter persisted;
    private final Counter flushFailures;

    public ClickRecorder(ClickEventRepository repository, ShortenerProperties props, Clock clock, MeterRegistry registry) {
        this.repository = repository;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(props.analytics().queueCapacity());
        this.batchSize = props.analytics().batchSize();
        this.ipHasher = new IpHasher(props.analytics().ipHashSalt());
        this.accepted = registry.counter("shortener.clicks", "outcome", "accepted");
        this.dropped = registry.counter("shortener.clicks", "outcome", "dropped");
        this.persisted = registry.counter("shortener.clicks", "outcome", "persisted");
        this.flushFailures = registry.counter("shortener.clicks.flush.failures");
        registry.gauge("shortener.clicks.queue.size", queue, BlockingQueue::size);
    }

    /** Never blocks and never throws: analytics must not break redirects. */
    public void record(long shortUrlId, String referrer, String userAgent, String clientIp) {
        try {
            Instant now = clock.instant();
            ClickEvent event = new ClickEvent(shortUrlId, now, LocalDate.ofInstant(now, ZoneOffset.UTC),
                    ReferrerParser.host(referrer), UserAgentClassifier.classify(userAgent), ipHasher.hash(clientIp));
            if (queue.offer(event)) {
                accepted.increment();
            } else {
                dropped.increment();
            }
        } catch (RuntimeException e) {
            dropped.increment();
            log.warn("Failed to capture click for link {}", shortUrlId, e);
        }
    }

    /** Drains the queue in batches. Returns the number of events persisted. */
    @Scheduled(fixedDelayString = "${shortener.analytics.flush-interval-ms:1000}")
    public int flush() {
        int total = 0;
        List<ClickEvent> batch = new ArrayList<>(batchSize);
        while (queue.drainTo(batch, batchSize) > 0) {
            try {
                repository.saveAll(batch);
                persisted.increment(batch.size());
                total += batch.size();
            } catch (RuntimeException e) {
                // We drop the batch instead of re-queueing it, so a bad batch can't block the queue forever.
                flushFailures.increment();
                dropped.increment(batch.size());
                log.error("Dropped {} click events after a flush failure", batch.size(), e);
            }
            batch = new ArrayList<>(batchSize);
        }
        return total;
    }

    int pending() {
        return queue.size();
    }

    @PreDestroy
    void drainOnShutdown() {
        int n = flush();
        log.info("Flushed {} click events on shutdown", n);
    }
}
