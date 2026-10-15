package com.occpl.platform.notification.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param from sender address
 * @param maxAttempts delivery attempts before a message is marked FAILED
 * @param batchSize messages claimed per dispatcher run
 */
@ConfigurationProperties("occpl.notifications")
record NotificationProperties(@DefaultValue("OCCPL Innovations <no-reply@occpl.local>") String from,
        @DefaultValue("8") int maxAttempts, @DefaultValue("50") int batchSize,
        @DefaultValue("10s") Duration dispatchInterval) {
}
