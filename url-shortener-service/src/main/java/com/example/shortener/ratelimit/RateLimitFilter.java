package com.example.shortener.ratelimit;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.security.ApiKeyAuthFilter;
import com.example.shortener.security.ProblemResponses;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Two limiters: one per API key for {@code /api/**} (it runs after authentication, so the key is known),
 * and one per client IP for public redirects.
 *
 * <p>Uses {@code getRemoteAddr()}. Behind a load balancer, set {@code server.forward-headers-strategy=native}
 * so the real client IP is used. We never trust a raw X-Forwarded-For header ourselves.
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketRateLimiter apiLimiter;
    private final TokenBucketRateLimiter redirectLimiter;
    private final Counter rejectedApi;
    private final Counter rejectedRedirect;

    public RateLimitFilter(ShortenerProperties props, MeterRegistry registry) {
        ShortenerProperties.RateLimit rl = props.rateLimit();
        this.apiLimiter = new TokenBucketRateLimiter(rl.apiCapacity(), rl.apiRefillPerSecond());
        this.redirectLimiter = new TokenBucketRateLimiter(rl.redirectCapacity(), rl.redirectRefillPerSecond());
        this.rejectedApi = registry.counter("shortener.ratelimit.rejected", "scope", "api");
        this.rejectedRedirect = registry.counter("shortener.ratelimit.rejected", "scope", "redirect");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean api = request.getRequestURI().startsWith("/api/");
        TokenBucketRateLimiter.Decision decision = api
                ? apiLimiter.tryAcquire("key:" + request.getAttribute(ApiKeyAuthFilter.OWNER_ATTRIBUTE))
                : redirectLimiter.tryAcquire("ip:" + request.getRemoteAddr());

        if (!decision.allowed()) {
            (api ? rejectedApi : rejectedRedirect).increment();
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            ProblemResponses.write(response, 429, "rate-limited", "Too Many Requests",
                    "Rate limit exceeded; retry after " + decision.retryAfterSeconds() + "s");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Keeps memory bounded by dropping buckets for clients we haven't seen recently. */
    @Scheduled(fixedDelayString = "PT5M")
    public void evictIdleBuckets() {
        apiLimiter.evictIdle(Duration.ofMinutes(10));
        redirectLimiter.evictIdle(Duration.ofMinutes(10));
    }
}
