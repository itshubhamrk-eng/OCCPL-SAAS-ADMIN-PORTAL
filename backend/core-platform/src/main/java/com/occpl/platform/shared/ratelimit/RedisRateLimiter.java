package com.occpl.platform.shared.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Token-bucket limiter backed by Redis (Bucket4j, compare-and-swap). The connection is opened lazily
 * and every Redis failure <b>fails open</b>: availability of the API is preferred over strict
 * limiting, Nginx keeps a coarse per-IP limit, and failures are counted in
 * {@code occpl.ratelimit.errors} for alerting.
 */
class RedisRateLimiter implements RateLimiter, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(10);

    private final RateLimitProperties properties;
    private final RedisClient client;
    private final Map<Long, BucketConfiguration> configurations = new ConcurrentHashMap<>();
    private final Counter rejected;
    private final Counter errors;

    private volatile StatefulRedisConnection<String, byte[]> connection;
    private volatile ProxyManager<String> proxyManager;
    private volatile Instant nextConnectAttempt = Instant.EPOCH;

    RedisRateLimiter(RateLimitProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        RedisURI uri = RedisURI.create(properties.redisUrl());
        uri.setTimeout(properties.redisTimeout());
        this.client = RedisClient.create(uri);
        this.client.setOptions(ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled(properties.redisTimeout()))
                .build());
        this.rejected = meterRegistry.counter("occpl.ratelimit.rejected");
        this.errors = meterRegistry.counter("occpl.ratelimit.errors");
    }

    @Override
    public Decision tryConsume(String key, long limitPerMinute) {
        ProxyManager<String> manager = proxyManager();
        if (manager == null) {
            return Decision.UNAVAILABLE;
        }
        try {
            BucketConfiguration configuration = configurations.computeIfAbsent(limitPerMinute,
                    limit -> BucketConfiguration.builder()
                            .addLimit(l -> l.capacity(limit).refillGreedy(limit, Duration.ofMinutes(1)))
                            .build());
            ConsumptionProbe probe = manager.getProxy(key, () -> configuration).tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                return new Decision(true, probe.getRemainingTokens(), 0);
            }
            rejected.increment();
            long retryAfter = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
            return new Decision(false, 0, retryAfter);
        } catch (RuntimeException e) {
            errors.increment();
            log.warn("Rate limiter unavailable, allowing request: {}", e.toString());
            return Decision.UNAVAILABLE;
        }
    }

    private ProxyManager<String> proxyManager() {
        ProxyManager<String> manager = proxyManager;
        if (manager != null) {
            return manager;
        }
        synchronized (this) {
            if (proxyManager == null && Instant.now().isAfter(nextConnectAttempt)) {
                try {
                    connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
                    proxyManager = Bucket4jLettuce.casBasedBuilder(connection)
                            .expirationAfterWrite(
                                    ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(1)))
                            .build();
                    log.info("Rate limiter connected to Redis");
                } catch (RuntimeException e) {
                    errors.increment();
                    nextConnectAttempt = Instant.now().plus(RECONNECT_BACKOFF);
                    log.warn("Rate limiter cannot connect to Redis, failing open: {}", e.toString());
                }
            }
            return proxyManager;
        }
    }

    @Override
    public void destroy() {
        if (connection != null) {
            connection.close();
        }
        client.shutdown();
    }
}
