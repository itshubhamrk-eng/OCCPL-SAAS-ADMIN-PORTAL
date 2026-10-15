package com.occpl.platform.notification.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Claims due messages. {@code SKIP LOCKED} lets several application instances dispatch in
     * parallel without sending the same message twice.
     */
    @Query(value = """
            select * from notification
            where status = 'QUEUED' and next_attempt_at <= :now
            order by next_attempt_at
            limit :limit
            for update skip locked""", nativeQuery = true)
    List<Notification> claimDue(Instant now, int limit);
}
