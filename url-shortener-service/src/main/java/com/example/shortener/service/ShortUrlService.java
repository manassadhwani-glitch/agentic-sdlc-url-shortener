package com.example.shortener.service;

import com.example.shortener.code.ShortCodeCodec;
import com.example.shortener.domain.LinkStatus;
import com.example.shortener.domain.ShortUrl;
import com.example.shortener.repository.ShortUrlRepository;
import com.example.shortener.validation.AliasPolicy;
import com.example.shortener.validation.UrlValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Core link lifecycle: create, resolve, read, disable. */
@Service
public class ShortUrlService {

    private final ShortUrlRepository repository;
    private final ShortCodeCodec codec;
    private final UrlValidator urlValidator;
    private final LinkCache cache;
    private final Clock clock;

    public ShortUrlService(ShortUrlRepository repository, ShortCodeCodec codec, UrlValidator urlValidator,
                           LinkCache cache, Clock clock) {
        this.repository = repository;
        this.codec = codec;
        this.urlValidator = urlValidator;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Creates a link.
     *
     * <p>For custom aliases, {@code existsByShortCode} is a fast path that gives a friendly error. The real guard
     * against two concurrent requests for the same alias is the UNIQUE constraint: the second insert fails with
     * {@code DataIntegrityViolationException}, which the caller turns into 409 (see {@code LinkCreationFacade}).
     */
    @Transactional
    public ShortUrl create(String longUrl, String customAlias, String ownerKeyId) {
        String target = urlValidator.validate(longUrl);

        if (customAlias != null) {
            AliasPolicy.validate(customAlias);
            if (repository.existsByShortCode(customAlias)) {
                throw new AliasTakenException(customAlias);
            }
            return repository.saveAndFlush(ShortUrl.withAlias(target, customAlias, ownerKeyId, clock.instant()));
        }

        // Insert first to get the sequence id, then derive the code from it. Same transaction.
        ShortUrl link = repository.saveAndFlush(ShortUrl.generated(target, ownerKeyId, clock.instant()));
        link.assignShortCode(codec.encode(link.getId()));
        return link;
    }

    /** Hot path for redirects. Served from cache when possible. */
    public CachedLink resolve(String code) {
        CachedLink link = cache.get(code, c -> repository.findByShortCode(c).map(CachedLink::from).orElse(null));
        if (link == null) {
            throw new LinkNotFoundException(code);
        }
        if (link.status() != LinkStatus.ACTIVE) {
            throw new LinkGoneException(code);
        }
        return link;
    }

    /**
     * Returns a link only if the caller owns it. Other tenants get 404 rather than 403,
     * so the API does not reveal which codes exist.
     */
    @Transactional(readOnly = true)
    public ShortUrl getOwned(String code, String ownerKeyId) {
        return repository.findByShortCode(code)
                .filter(link -> link.isOwnedBy(ownerKeyId))
                .orElseThrow(() -> new LinkNotFoundException(code));
    }

    @Transactional
    public void disable(String code, String ownerKeyId) {
        ShortUrl link = repository.findByShortCode(code)
                .filter(l -> l.isOwnedBy(ownerKeyId))
                .orElseThrow(() -> new LinkNotFoundException(code));
        link.disable();
        cache.invalidate(code);
    }
}
