package com.occpl.platform.subscription.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Time-based lifecycle: trial expiry, period end (PAST_DUE or scheduled expiry), grace elapsed
 * (SUSPENDED), long suspension (EXPIRED). Each subscription is handled in its own transaction under a
 * row lock and re-checked, so concurrent instances and repeated runs are safe.
 */
@Component
public class SubscriptionSweeper {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionSweeper.class);
    private static final int BATCH = 200;

    private final CompanySubscriptionRepository subscriptions;
    private final SubscriptionService service;
    private final SubscriptionProperties properties;
    private final Clock clock;

    SubscriptionSweeper(CompanySubscriptionRepository subscriptions, SubscriptionService service,
            SubscriptionProperties properties, Clock clock) {
        this.subscriptions = subscriptions;
        this.service = service;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${occpl.subscriptions.sweep-interval:5m}", initialDelayString = "1m")
    void scheduled() {
        sweep();
    }

    /** Runs all rules once; returns the number of subscriptions changed. */
    public int sweep() {
        int changed = 0;
        int round;
        do {
            Instant now = clock.instant();
            Set<UUID> due = new LinkedHashSet<>();
            due.addAll(subscriptions.trialsEnded(now, Limit.of(BATCH)));
            due.addAll(subscriptions.periodsEnded(now, Limit.of(BATCH)));
            due.addAll(subscriptions.graceElapsed(now.minus(properties.gracePeriod()), Limit.of(BATCH)));
            due.addAll(subscriptions.longSuspended(now.minus(properties.suspendedExpiry()), Limit.of(BATCH)));
            round = 0;
            for (UUID id : due) {
                try {
                    if (service.applyDueTransition(id)) {
                        round++;
                    }
                } catch (RuntimeException e) {
                    log.error("Subscription sweep failed for {}", id, e);
                }
            }
            changed += round;
        } while (round > 0);
        if (changed > 0) {
            log.info("Subscription sweep changed {} subscriptions", changed);
        }
        return changed;
    }
}
