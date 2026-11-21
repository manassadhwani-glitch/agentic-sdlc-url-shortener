package com.example.shortener.api;

import com.example.shortener.analytics.AnalyticsService;
import com.example.shortener.api.dto.CreateUrlRequest;
import com.example.shortener.api.dto.CreateUrlResponse;
import com.example.shortener.api.dto.UrlDetailsResponse;
import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.idempotency.LinkCreationFacade;
import com.example.shortener.security.ApiKeyAuthFilter;
import com.example.shortener.service.BadRequestException;
import com.example.shortener.service.ShortUrlService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final LinkCreationFacade creation;
    private final ShortUrlService shortUrlService;
    private final AnalyticsService analyticsService;
    private final String baseUrl;

    public UrlController(LinkCreationFacade creation, ShortUrlService shortUrlService,
                         AnalyticsService analyticsService, ShortenerProperties props) {
        this.creation = creation;
        this.shortUrlService = shortUrlService;
        this.analyticsService = analyticsService;
        this.baseUrl = props.normalizedBaseUrl();
    }

    @PostMapping
    public ResponseEntity<CreateUrlResponse> create(
            @Valid @RequestBody CreateUrlRequest request,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestAttribute(ApiKeyAuthFilter.OWNER_ATTRIBUTE) String owner) {

        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new BadRequestException(IDEMPOTENCY_HEADER + " must be 1-128 chars of [A-Za-z0-9._:-]");
        }
        LinkCreationFacade.Result result = creation.create(request, owner, idempotencyKey);
        CreateUrlResponse body = result.response();
        return ResponseEntity.created(URI.create("/api/v1/urls/" + body.shortCode()))
                .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(body);
    }

    @GetMapping("/{code}")
    public UrlDetailsResponse get(@PathVariable String code,
                                  @RequestAttribute(ApiKeyAuthFilter.OWNER_ATTRIBUTE) String owner) {
        return UrlDetailsResponse.from(shortUrlService.getOwned(code, owner), baseUrl);
    }

    @GetMapping("/{code}/stats")
    public AnalyticsService.LinkStats stats(
            @PathVariable String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestAttribute(ApiKeyAuthFilter.OWNER_ATTRIBUTE) String owner) {
        return analyticsService.stats(code, owner, from, to);
    }

    /** Soft delete: the link stops redirecting (410) but its history and analytics are kept. */
    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable String code,
                        @RequestAttribute(ApiKeyAuthFilter.OWNER_ATTRIBUTE) String owner) {
        shortUrlService.disable(code, owner);
    }
}
