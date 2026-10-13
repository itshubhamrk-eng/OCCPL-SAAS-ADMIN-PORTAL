package com.occpl.platform.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RedisRateLimiterTest {

    @Test
    void failsOpenAndCountsErrorWhenRedisIsUnreachable() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitProperties properties = new RateLimitProperties(true, "redis://127.0.0.1:1", 10, 10,
                Duration.ofMillis(200));
        RedisRateLimiter limiter = new RedisRateLimiter(properties, registry);
        try {
            RateLimiter.Decision first = limiter.tryConsume("rl:test", 1);
            RateLimiter.Decision second = limiter.tryConsume("rl:test", 1);

            assertThat(first.allowed()).isTrue();
            assertThat(first.remaining()).isEqualTo(-1);
            assertThat(second.allowed()).as("still open: a limit of 1 would otherwise reject").isTrue();
            assertThat(registry.counter("occpl.ratelimit.errors").count()).isEqualTo(1.0)
                    .as("reconnect attempts are throttled, not repeated per request");
        } finally {
            limiter.destroy();
        }
    }
}
