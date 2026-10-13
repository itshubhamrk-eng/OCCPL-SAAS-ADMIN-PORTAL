package com.occpl.platform.subscription.internal;

import static com.occpl.platform.subscription.api.SubscriptionStatus.ACTIVE;
import static com.occpl.platform.subscription.api.SubscriptionStatus.CANCELLED;
import static com.occpl.platform.subscription.api.SubscriptionStatus.EXPIRED;
import static com.occpl.platform.subscription.api.SubscriptionStatus.PAST_DUE;
import static com.occpl.platform.subscription.api.SubscriptionStatus.SUSPENDED;
import static com.occpl.platform.subscription.api.SubscriptionStatus.TRIAL;

import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.subscription.api.SubscriptionStatus;
import java.util.EnumSet;
import java.util.Set;

/**
 * The only allowed subscription status transitions (DATABASE.md §5). Everything not listed is rejected
 * with 422 INVALID_STATE_TRANSITION.
 *
 * <pre>
 * ACTIVATE        TRIAL                       → ACTIVE     trial converted (payment/manual activation)
 * RENEW           ACTIVE, PAST_DUE, SUSPENDED → ACTIVE     next period paid
 * MARK_PAST_DUE   ACTIVE                      → PAST_DUE   period ended without renewal (grace starts)
 * SUSPEND         ACTIVE, PAST_DUE            → SUSPENDED  grace elapsed, or manual suspension
 * CANCEL          TRIAL, ACTIVE, PAST_DUE, SUSPENDED → CANCELLED  immediate cancellation
 * EXPIRE          TRIAL, ACTIVE, SUSPENDED    → EXPIRED    trial ended / scheduled cancellation / long suspension
 * </pre>
 */
final class SubscriptionStateMachine {

    enum Event {
        ACTIVATE(EnumSet.of(TRIAL), ACTIVE),
        RENEW(EnumSet.of(ACTIVE, PAST_DUE, SUSPENDED), ACTIVE),
        MARK_PAST_DUE(EnumSet.of(ACTIVE), PAST_DUE),
        SUSPEND(EnumSet.of(ACTIVE, PAST_DUE), SUSPENDED),
        CANCEL(EnumSet.of(TRIAL, ACTIVE, PAST_DUE, SUSPENDED), CANCELLED),
        EXPIRE(EnumSet.of(TRIAL, ACTIVE, SUSPENDED), EXPIRED);

        final Set<SubscriptionStatus> from;
        final SubscriptionStatus to;

        Event(Set<SubscriptionStatus> from, SubscriptionStatus to) {
            this.from = from;
            this.to = to;
        }
    }

    private SubscriptionStateMachine() {
    }

    static boolean allowed(SubscriptionStatus from, Event event) {
        return event.from.contains(from);
    }

    static SubscriptionStatus next(SubscriptionStatus from, Event event) {
        if (!allowed(from, event)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot " + event.name().toLowerCase().replace('_', ' ') + " a subscription that is " + from + ".");
        }
        return event.to;
    }
}
