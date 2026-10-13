package com.occpl.platform.membership.internal;

import com.occpl.platform.access.api.CompanyAccess;
import com.occpl.platform.access.api.MembershipAccess;
import com.occpl.platform.membership.api.MembershipDirectory;
import com.occpl.platform.membership.api.MembershipStatus;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaMembershipDirectory implements MembershipDirectory {

    private final CompanyMembershipRepository memberships;
    private final CompanyAccess companyAccess;

    JpaMembershipDirectory(CompanyMembershipRepository memberships, CompanyAccess companyAccess) {
        this.memberships = memberships;
        this.companyAccess = companyAccess;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActiveMember> activeMember(UUID companyId, UUID userId) {
        return memberships.findByCompanyIdAndUserId(companyId, userId)
                .filter(m -> m.status() == MembershipStatus.ACTIVE)
                .map(m -> {
                    MembershipAccess access = companyAccess.forMemberships(Set.of(m.id()))
                            .getOrDefault(m.id(), MembershipAccess.NONE);
                    return new ActiveMember(m.id(), access.roleCodes(), access.permissions());
                });
    }
}
