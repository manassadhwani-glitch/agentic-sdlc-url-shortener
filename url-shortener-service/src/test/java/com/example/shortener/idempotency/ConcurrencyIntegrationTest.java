package com.example.shortener.idempotency;

import com.example.shortener.api.dto.CreateUrlRequest;
import com.example.shortener.repository.ShortUrlRepository;
import com.example.shortener.service.AliasTakenException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races that a check-then-insert design gets wrong. All threads start at the same moment (latch),
 * so they all pass the existence pre-check, and the database constraints have to decide the winner.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrencyIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private LinkCreationFacade facade;
    @Autowired
    private ShortUrlRepository repository;

    @Test
    void concurrentRequestsForSameAliasProduceExactlyOneLink() throws Exception {
        String alias = "race-" + UUID.randomUUID().toString().substring(0, 8);
        List<Object> outcomes = runConcurrently(() ->
                facade.create(new CreateUrlRequest("https://example.com/race", alias), "owner-x", null));

        long successes = outcomes.stream().filter(o -> o instanceof LinkCreationFacade.Result).count();
        long conflicts = outcomes.stream().filter(o -> o instanceof AliasTakenException).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(THREADS - 1);
        assertThat(repository.existsByShortCode(alias)).isTrue();
    }

    @Test
    void concurrentRetriesWithSameIdempotencyKeyCreateOneLink() throws Exception {
        String key = UUID.randomUUID().toString();
        long before = repository.count();

        List<Object> outcomes = runConcurrently(() ->
                facade.create(new CreateUrlRequest("https://example.com/idem-race", null), "owner-y", key));

        Set<String> codes = new HashSet<>();
        for (Object o : outcomes) {
            assertThat(o).isInstanceOf(LinkCreationFacade.Result.class);
            codes.add(((LinkCreationFacade.Result) o).response().shortCode());
        }
        assertThat(codes).hasSize(1);
        assertThat(repository.count() - before).isEqualTo(1);
    }

    private List<Object> runConcurrently(Callable<Object> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : futures) {
            try {
                outcomes.add(f.get());
            } catch (ExecutionException e) {
                outcomes.add(e.getCause());
            }
        }
        pool.shutdown();
        return outcomes;
    }
}
