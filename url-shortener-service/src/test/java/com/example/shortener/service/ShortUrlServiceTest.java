package com.example.shortener.service;

import com.example.shortener.TestProps;
import com.example.shortener.code.ShortCodeCodec;
import com.example.shortener.domain.LinkStatus;
import com.example.shortener.domain.ShortUrl;
import com.example.shortener.repository.ShortUrlRepository;
import com.example.shortener.validation.InvalidUrlException;
import com.example.shortener.validation.UrlValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortUrlServiceTest {

    private static final String OWNER = "owner-a";
    private final ShortUrlRepository repository = mock(ShortUrlRepository.class);
    private final ShortCodeCodec codec = new ShortCodeCodec(0x1234);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);
    private ShortUrlService service;

    @BeforeEach
    void setUp() {
        LinkCache cache = new LinkCache(TestProps.defaults(), new SimpleMeterRegistry());
        service = new ShortUrlService(repository, codec, new UrlValidator("sho.rt", false), cache, clock);
        when(repository.saveAndFlush(any(ShortUrl.class))).thenAnswer(inv -> {
            ShortUrl link = inv.getArgument(0);
            ReflectionTestUtils.setField(link, "id", 100_000L);
            return link;
        });
    }

    @Test
    void generatedLinkGetsCodeDerivedFromId() {
        ShortUrl link = service.create("https://example.com", null, OWNER);
        assertThat(link.getShortCode()).isEqualTo(codec.encode(100_000L));
        assertThat(link.isCustomAlias()).isFalse();
        assertThat(link.getStatus()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(link.getCreatedAt()).isEqualTo(clock.instant());
    }

    @Test
    void customAliasIsUsedAsIs() {
        ShortUrl link = service.create("https://example.com", "my-promo", OWNER);
        assertThat(link.getShortCode()).isEqualTo("my-promo");
        assertThat(link.isCustomAlias()).isTrue();
    }

    @Test
    void takenAliasFailsFastWithoutInsert() {
        when(repository.existsByShortCode("my-promo")).thenReturn(true);
        assertThatThrownBy(() -> service.create("https://example.com", "my-promo", OWNER))
                .isInstanceOf(AliasTakenException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void invalidUrlIsRejectedBeforeAnyDatabaseWork() {
        assertThatThrownBy(() -> service.create("http://127.0.0.1/x", null, OWNER))
                .isInstanceOf(InvalidUrlException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void resolveUsesCacheAfterFirstLookup() {
        ShortUrl link = persisted("abc-123");
        when(repository.findByShortCode("abc-123")).thenReturn(Optional.of(link));

        service.resolve("abc-123");
        service.resolve("abc-123");

        verify(repository, times(1)).findByShortCode("abc-123");
    }

    @Test
    void unknownCodesAreNotCached() {
        when(repository.findByShortCode("nope-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve("nope-1")).isInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> service.resolve("nope-1")).isInstanceOf(LinkNotFoundException.class);
        verify(repository, times(2)).findByShortCode("nope-1");
    }

    @Test
    void disablingInvalidatesCacheAndResolveReturnsGone() {
        ShortUrl link = persisted("abc-123");
        when(repository.findByShortCode("abc-123")).thenReturn(Optional.of(link));
        service.resolve("abc-123"); // warm cache

        service.disable("abc-123", OWNER);

        assertThat(link.getStatus()).isEqualTo(LinkStatus.DISABLED);
        assertThatThrownBy(() -> service.resolve("abc-123")).isInstanceOf(LinkGoneException.class);
    }

    @Test
    void otherTenantsCannotSeeOrDisableLinks() {
        ShortUrl link = persisted("abc-123");
        when(repository.findByShortCode("abc-123")).thenReturn(Optional.of(link));

        assertThatThrownBy(() -> service.getOwned("abc-123", "intruder")).isInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> service.disable("abc-123", "intruder")).isInstanceOf(LinkNotFoundException.class);
        assertThat(link.getStatus()).isEqualTo(LinkStatus.ACTIVE);
    }

    private ShortUrl persisted(String alias) {
        ShortUrl link = ShortUrl.withAlias("https://example.com", alias, OWNER, clock.instant());
        ReflectionTestUtils.setField(link, "id", 5L);
        return link;
    }
}
