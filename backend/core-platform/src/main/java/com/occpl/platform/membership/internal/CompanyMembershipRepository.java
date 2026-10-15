package com.occpl.platform.membership.internal;

import com.occpl.platform.membership.api.MembershipStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface CompanyMembershipRepository extends JpaRepository<CompanyMembership, UUID> {

    Optional<CompanyMembership> findByCompanyIdAndUserId(UUID companyId, UUID userId);

    Optional<CompanyMembership> findByIdAndCompanyId(UUID id, UUID companyId);

    List<CompanyMembership> findByUserIdAndStatus(UUID userId, MembershipStatus status);

    List<CompanyMembership> findByUserIdOrderByCreatedAt(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from CompanyMembership m where m.id = :id and m.companyId = :companyId")
    Optional<CompanyMembership> lockByIdAndCompanyId(UUID id, UUID companyId);
}
