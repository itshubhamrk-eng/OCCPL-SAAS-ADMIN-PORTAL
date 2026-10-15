package com.occpl.platform.shared;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs (email dispatch, invitation expiry). Every job is safe to run on several
 * instances at once (row locks or idempotent updates). Disabled in tests, which trigger jobs directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "occpl.scheduling.enabled", matchIfMissing = true)
class SchedulingConfiguration {
}
