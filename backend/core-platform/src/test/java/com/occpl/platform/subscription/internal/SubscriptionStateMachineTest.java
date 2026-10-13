package com.occpl.platform.subscription.internal;

import static com.occpl.platform.subscription.api.SubscriptionStatus.ACTIVE;
import static com.occpl.platform.subscription.api.SubscriptionStatus.CANCELLED;
import static com.occpl.platform.subscription.api.SubscriptionStatus.EXPIRED;
import static com.occpl.platform.subscription.api.SubscriptionStatus.PAST_DUE;
import static com.occpl.platform.subscription.api.SubscriptionStatus.SUSPENDED;
import static com.occpl.platform.subscription.api.SubscriptionStatus.TRIAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.subscription.api.SubscriptionStatus;
import com.occpl.platform.subscription.internal.SubscriptionStateMachine.Event;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every (status, event) combination: the allowed ones move to the expected status, all others are rejected. */
class SubscriptionStateMachineTest {

    /** Expected table, written independently of the implementation (DATABASE.md §5). */
    private static final Map<Event, Set<SubscriptionStatus>> ALLOWED = new EnumMap<>(Map.of(
            Event.ACTIVATE, EnumSet.of(TRIAL),
            Event.RENEW, EnumSet.of(ACTIVE, PAST_DUE, SUSPENDED),
            Event.MARK_PAST_DUE, EnumSet.of(ACTIVE),
            Event.SUSPEND, EnumSet.of(ACTIVE, PAST_DUE),
            Event.CANCEL, EnumSet.of(TRIAL, ACTIVE, PAST_DUE, SUSPENDED),
            Event.EXPIRE, EnumSet.of(TRIAL, ACTIVE, SUSPENDED)));

    private static final Map<Event, SubscriptionStatus> TARGET = new EnumMap<>(Map.of(
            Event.ACTIVATE, ACTIVE, Event.RENEW, ACTIVE, Event.MARK_PAST_DUE, PAST_DUE, Event.SUSPEND, SUSPENDED,
            Event.CANCEL, CANCELLED, Event.EXPIRE, EXPIRED));

    static Stream<Arguments> everyCombination() {
        return Stream.of(SubscriptionStatus.values())
                .flatMap(status -> Stream.of(Event.values()).map(event -> Arguments.of(status, event)));
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("everyCombination")
    void transitionTable(SubscriptionStatus from, Event event) {
        if (ALLOWED.get(event).contains(from)) {
            assertThat(SubscriptionStateMachine.next(from, event)).isEqualTo(TARGET.get(event));
        } else {
            assertThatThrownBy(() -> SubscriptionStateMachine.next(from, event))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        }
    }

    @ParameterizedTest(name = "terminal {0}")
    @MethodSource("terminalStatuses")
    void terminalStatusesAllowNothing(SubscriptionStatus terminal) {
        for (Event event : Event.values()) {
            assertThat(SubscriptionStateMachine.allowed(terminal, event)).isFalse();
        }
    }

    static Stream<SubscriptionStatus> terminalStatuses() {
        return Stream.of(CANCELLED, EXPIRED);
    }
}
