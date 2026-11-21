package com.example.shortener.security;

import com.example.shortener.config.ShortenerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Requires a valid {@code X-API-Key} on every {@code /api/**} call. Public redirects are not authenticated.
 * On success, the caller's key id is stored as a request attribute; controllers use it for tenant scoping.
 */
@Component
@Order(1)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String OWNER_ATTRIBUTE = "shortener.ownerKeyId";

    private final ApiKeys apiKeys;

    public ApiKeyAuthFilter(ShortenerProperties props) {
        this.apiKeys = new ApiKeys(props.security().apiKeys());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (!apiKeys.isValid(key)) {
            ProblemResponses.write(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "Missing or invalid " + HEADER + " header");
            return;
        }
        request.setAttribute(OWNER_ATTRIBUTE, ApiKeys.keyId(key));
        chain.doFilter(request, response);
    }
}
