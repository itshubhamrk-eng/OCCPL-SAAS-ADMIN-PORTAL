package com.occpl.platform.membership.internal;

import com.occpl.platform.membership.api.MembershipStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CompanyMembershipRepository extends JpaRepository<CompanyMembership, UUID> {

    Optional<CompanyMembership> findByCompanyIdAndUserId(UUID companyId, UUID userId);

    List<CompanyMembership> findByUserIdAndStatus(UUID userId, MembershipStatus status);
}
