package com.occpl.platform.membership.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Invitation> findByTokenHash(String tokenHash);

    Optional<Invitation> findByIdAndCompanyId(UUID id, UUID companyId);

    Optional<Invitation> findFirstByMembershipIdAndStatus(UUID membershipId, Invitation.Status status);

    Optional<Invitation> findFirstByMembershipIdOrderByCreatedAtDesc(UUID membershipId);

    @Query("select i from Invitation i where i.companyId = :companyId and (:status is null or i.status = :status)")
    Page<Invitation> findByCompany(UUID companyId, Invitation.Status status, Pageable pageable);

    /** Expires at most {@code limit} overdue invitations; returns the number updated (idempotent, multi-instance safe). */
    @Modifying
    @Query(value = """
            update invitation set status = 'EXPIRED', updated_at = :now, version = version + 1
            where status = 'PENDING' and expires_at <= :now
            limit :limit""", nativeQuery = true)
    int expireOverdue(Instant now, int limit);
}
