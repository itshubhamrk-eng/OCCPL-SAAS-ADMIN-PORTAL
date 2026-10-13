package com.occpl.platform.shared.ratelimit;

import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.error.Problems;
import com.occpl.platform.shared.security.Actor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies per-user (authenticated) or per-IP (anonymous) limits to {@code /api/**}. Registered inside
 * the security filter chain after bearer-token authentication, so the user is known.
 * The client IP honours X-Forwarded-For only because the application is reachable solely through
 * the reverse proxy (DEPLOYMENT.md).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final RateLimitProperties properties;
    private final JsonMapper jsonMapper;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties, JsonMapper jsonMapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimiter.Decision decision = Actor.current()
                .map(actor -> limiter.tryConsume("rl:api:user:" + actor.userId(), properties.authenticatedPerMinute()))
                .orElseGet(() -> limiter.tryConsume("rl:api:ip:" + request.getRemoteAddr(),
                        properties.anonymousPerMinute()));
        if (decision.remaining() >= 0) {
            response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        }
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        ProblemDetail problem = Problems.of(ErrorCode.RATE_LIMITED,
                "Too many requests. Retry after " + decision.retryAfterSeconds() + " seconds.", request.getRequestURI());
        response.setStatus(problem.getStatus());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
