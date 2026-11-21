package com.example.shortener.analytics;

import com.example.shortener.TestProps;
import com.example.shortener.domain.ClickEvent;
import com.example.shortener.repository.ClickEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClickRecorderTest {

    private final ClickEventRepository repository = mock(ClickEventRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-19T23:30:00Z"), ZoneOffset.UTC);

    private ClickRecorder recorder(int capacity, int batch) {
        return new ClickRecorder(repository, TestProps.withQueue(capacity, batch), clock, registry);
    }

    private double counter(String outcome) {
        return registry.get("shortener.clicks").tag("outcome", outcome).counter().count();
    }

    @Test
    @SuppressWarnings("unchecked")
    void capturesPrivacySafeAttributes() {
        ClickRecorder recorder = recorder(10, 10);
        recorder.record(7L, "https://news.example.com/a?x=1", "curl/8.0", "203.0.113.9");
        recorder.flush();

        ArgumentCaptor<List<ClickEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        ClickEvent e = captor.getValue().get(0);
        assertThat(e.getShortUrlId()).isEqualTo(7L);
        assertThat(e.getReferrerHost()).isEqualTo("news.example.com");
        assertThat(e.getUaFamily()).isEqualTo("CLI/Library");
        assertThat(e.getIpHash()).doesNotContain("203.0.113.9").hasSize(32);
        assertThat(e.getOccurredOn()).isEqualTo(LocalDate.of(2026, 11, 19));
    }

    @Test
    void dropsAndCountsWhenQueueIsFull() {
        ClickRecorder recorder = recorder(2, 10);
        for (int i = 0; i < 5; i++) {
            recorder.record(1L, null, null, "198.51.100.1");
        }
        assertThat(recorder.pending()).isEqualTo(2);
        assertThat(counter("accepted")).isEqualTo(2);
        assertThat(counter("dropped")).isEqualTo(3);
    }

    @Test
    void flushesInBatches() {
        ClickRecorder recorder = recorder(100, 4);
        for (int i = 0; i < 10; i++) {
            recorder.record(1L, null, null, "198.51.100.1");
        }
        assertThat(recorder.flush()).isEqualTo(10);
        verify(repository, times(3)).saveAll(anyList()); // 4 + 4 + 2
        assertThat(recorder.pending()).isZero();
        assertThat(counter("persisted")).isEqualTo(10);
    }

    @Test
    void flushFailureIsContainedAndCounted() {
        when(repository.saveAll(anyList())).thenThrow(new RuntimeException("db down"));
        ClickRecorder recorder = recorder(100, 10);
        recorder.record(1L, null, null, "198.51.100.1");

        assertThat(recorder.flush()).isZero();
        assertThat(registry.get("shortener.clicks.flush.failures").counter().count()).isEqualTo(1);
        assertThat(counter("dropped")).isEqualTo(1);
        assertThat(recorder.pending()).isZero();
    }
}
