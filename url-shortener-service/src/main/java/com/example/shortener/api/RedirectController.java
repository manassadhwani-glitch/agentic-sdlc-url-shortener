package com.example.shortener.api;

import com.example.shortener.analytics.ClickRecorder;
import com.example.shortener.service.CachedLink;
import com.example.shortener.service.LinkNotFoundException;
import com.example.shortener.service.ShortUrlService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Public redirect endpoint. Uses 302 (not 301) so browsers don't cache the hop: every click is counted,
 * and disabling a link takes effect right away.
 */
@RestController
public class RedirectController {

    private static final int MIN_CODE_LENGTH = 3;
    private static final int MAX_CODE_LENGTH = 64;

    private final ShortUrlService shortUrlService;
    private final ClickRecorder clickRecorder;

    public RedirectController(ShortUrlService shortUrlService, ClickRecorder clickRecorder) {
        this.shortUrlService = shortUrlService;
        this.clickRecorder = clickRecorder;
    }

    // Simple regex (no {m,n} quantifier inside the path variable); length is checked in code.
    @GetMapping("/{code:[A-Za-z0-9_-]+}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        if (code.length() < MIN_CODE_LENGTH || code.length() > MAX_CODE_LENGTH) {
            throw new LinkNotFoundException(code);
        }
        CachedLink link = shortUrlService.resolve(code);
        clickRecorder.record(link.id(), request.getHeader(HttpHeaders.REFERER),
                request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.longUrl()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0")
                .header("Referrer-Policy", "no-referrer-when-downgrade")
                .build();
    }
}
