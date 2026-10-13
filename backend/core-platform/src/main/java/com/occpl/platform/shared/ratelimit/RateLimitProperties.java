package com.occpl.platform.shared.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API rate limits, shared across instances through Redis (ADR-0006).
 *
 * @param authenticatedPerMinute requests per minute per user
 * @param anonymousPerMinute requests per minute per client IP for requests without a valid token
 * @param redisTimeout maximum time a request waits for Redis before failing open
 */
@ConfigurationProperties("occpl.rate-limit")
public record RateLimitProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("redis://localhost:6379") String redisUrl,
        @DefaultValue("600") long authenticatedPerMinute,
        @DefaultValue("60") long anonymousPerMinute,
        @DefaultValue("200ms") Duration redisTimeout) {
}
