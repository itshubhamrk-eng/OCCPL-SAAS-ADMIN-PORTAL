package com.occpl.platform.shared.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnBooleanProperty("occpl.rate-limit.enabled")
class RateLimitConfiguration {

    @Bean
    RedisRateLimiter redisRateLimiter(RateLimitProperties properties, MeterRegistry meterRegistry) {
        return new RedisRateLimiter(properties, meterRegistry);
    }

    /** Added to the security filter chain by SecurityConfiguration. */
    @Bean
    RateLimitFilter rateLimitFilter(RateLimiter limiter, RateLimitProperties properties, JsonMapper jsonMapper) {
        return new RateLimitFilter(limiter, properties, jsonMapper);
    }

    /** Prevents Boot from also registering the filter in the servlet container's chain. */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
