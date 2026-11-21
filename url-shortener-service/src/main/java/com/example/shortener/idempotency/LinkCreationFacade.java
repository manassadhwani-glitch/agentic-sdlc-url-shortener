package com.example.shortener.idempotency;

import com.example.shortener.api.dto.CreateUrlRequest;
import com.example.shortener.api.dto.CreateUrlResponse;
import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.IdempotencyRecord;
import com.example.shortener.repository.IdempotencyRecordRepository;
import com.example.shortener.service.AliasTakenException;
import com.example.shortener.service.ShortUrlService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Create-link entry point. Adds idempotency and maps constraint-violation races to clean errors.
 *
 * <p>Idempotency flow:
 * <ol>
 *   <li>If a live record exists for (owner, key): same body → replay the stored response; different body → 422.</li>
 *   <li>Otherwise create the link <b>and</b> insert the idempotency record in one transaction.</li>
 *   <li>If two requests with the same key race, the record's primary key rejects the second one, its whole
 *       transaction rolls back (so no duplicate link is created), and it replays the winner's response.</li>
 * </ol>
 * This class is deliberately not {@code @Transactional}: it has to catch the exception <i>after</i> the
 * failed transaction has rolled back.
 */
@Service
public class LinkCreationFacade {

    private final ShortUrlService shortUrlService;
    private final IdempotencyRecordRepository records;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration ttl;
    private final String baseUrl;

    public LinkCreationFacade(ShortUrlService shortUrlService, IdempotencyRecordRepository records,
                              ObjectMapper objectMapper, PlatformTransactionManager txManager, Clock clock,
                              ShortenerProperties props) {
        this.shortUrlService = shortUrlService;
        this.records = records;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.ttl = props.idempotency().ttl();
        this.baseUrl = props.normalizedBaseUrl();
    }

    public Result create(CreateUrlRequest request, String ownerKeyId, String idempotencyKey) {
        String alias = request.normalizedAlias();

        if (idempotencyKey == null) {
            try {
                return new Result(createResponse(request, alias, ownerKeyId), false);
            } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
                throw mapConflict(alias, e);
            }
        }

        String recordKey = ownerKeyId + ":" + idempotencyKey;
        String requestHash = hash(request.longUrl().trim() + '\u0000' + (alias == null ? "" : alias));

        Optional<CreateUrlResponse> replay = findReplay(recordKey, requestHash);
        if (replay.isPresent()) {
            return new Result(replay.get(), true);
        }
        try {
            CreateUrlResponse response = tx.execute(status -> {
                CreateUrlResponse r = createResponse(request, alias, ownerKeyId);
                records.saveAndFlush(new IdempotencyRecord(recordKey, requestHash, toJson(r), clock.instant()));
                return r;
            });
            return new Result(response, false);
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            // Unique-key violation, or (on some databases) a lock timeout while waiting on the competing insert.
            Optional<CreateUrlResponse> winner = findReplay(recordKey, requestHash);
            if (winner.isPresent()) {
                return new Result(winner.get(), true);
            }
            throw mapConflict(alias, e);
        }
    }

    private CreateUrlResponse createResponse(CreateUrlRequest request, String alias, String ownerKeyId) {
        return CreateUrlResponse.from(shortUrlService.create(request.longUrl(), alias, ownerKeyId), baseUrl);
    }

    private RuntimeException mapConflict(String alias, DataAccessException e) {
        // The only unique constraint a create can hit (besides the idempotency key) is the short code.
        return alias != null ? new AliasTakenException(alias) : e;
    }

    private Optional<CreateUrlResponse> findReplay(String recordKey, String requestHash) {
        return tx.execute(status -> {
            Optional<IdempotencyRecord> existing = records.findById(recordKey);
            if (existing.isEmpty()) {
                return Optional.<CreateUrlResponse>empty();
            }
            IdempotencyRecord record = existing.get();
            if (record.isExpired(clock.instant(), ttl)) {
                records.delete(record);
                records.flush();
                return Optional.<CreateUrlResponse>empty();
            }
            if (!record.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException();
            }
            return Optional.of(fromJson(record.getResponseBody()));
        });
    }

    /** Hourly cleanup of expired records. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public int purgeExpired() {
        return records.deleteOlderThan(clock.instant().minus(ttl));
    }

    private String toJson(CreateUrlResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize response", e);
        }
    }

    private CreateUrlResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, CreateUrlResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt idempotency record", e);
        }
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Result(CreateUrlResponse response, boolean replayed) {
    }
}
