package com.occpl.platform.audit.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    List<AuditLogEntry> findByTargetTypeAndTargetIdOrderByOccurredAtAsc(String targetType, String targetId);
}
