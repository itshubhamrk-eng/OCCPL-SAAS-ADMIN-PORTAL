package com.occpl.platform.subscription.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SubscriptionHistoryRepository extends JpaRepository<SubscriptionHistory, UUID> {

    List<SubscriptionHistory> findBySubscriptionIdOrderByOccurredAtAsc(UUID subscriptionId);
}
