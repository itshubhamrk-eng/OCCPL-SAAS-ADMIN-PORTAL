package com.occpl.platform.membership.internal;

import com.occpl.platform.access.api.CompanyAccess;
import com.occpl.platform.access.api.MembershipAccess;
import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.company.api.CompanyStatus;
import com.occpl.platform.company.api.CompanySummary;
import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.membership.api.MembershipStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the current user's profile, platform access and company memberships for {@code GET /me}. */
@Service
public class MeService {

    private final CompanyMembershipRepository memberships;
    private final CompanyDirectory companies;
    private final CompanyAccess companyAccess;

    MeService(CompanyMembershipRepository memberships, CompanyDirectory companies, CompanyAccess companyAccess) {
        this.memberships = memberships;
        this.companies = companies;
        this.companyAccess = companyAccess;
    }

    public record MeView(UUID id, String email, String displayName, PlatformView platform,
            List<MembershipView> memberships) {
    }

    public record PlatformView(Set<String> roles, Set<String> permissions, boolean mfaRequired) {
    }

    public record MembershipView(UUID membershipId, UUID companyId, String companyCode, String companyName,
            CompanyStatus companyStatus, Set<String> roles, Set<String> permissions) {
    }

    @Transactional(readOnly = true)
    public MeView me(CurrentUser user) {
        List<CompanyMembership> active = memberships.findByUserIdAndStatus(user.userId(), MembershipStatus.ACTIVE);
        Map<UUID, CompanySummary> companyById = companies.findAll(
                active.stream().map(CompanyMembership::companyId).toList());
        Map<UUID, MembershipAccess> accessById = companyAccess.forMemberships(
                active.stream().map(CompanyMembership::id).toList());

        List<MembershipView> views = active.stream()
                .filter(m -> companyById.containsKey(m.companyId()))
                .filter(m -> companyById.get(m.companyId()).status() != CompanyStatus.DEACTIVATED)
                .map(m -> {
                    CompanySummary c = companyById.get(m.companyId());
                    MembershipAccess a = accessById.getOrDefault(m.id(), MembershipAccess.NONE);
                    return new MembershipView(m.id(), c.id(), c.code(), c.displayName(), c.status(),
                            new TreeSet<>(a.roleCodes()), new TreeSet<>(a.permissions()));
                })
                .sorted(Comparator.comparing(MembershipView::companyName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new MeView(user.userId(), user.email(), user.displayName(),
                new PlatformView(new TreeSet<>(user.platformRoles()), new TreeSet<>(user.platformPermissions()),
                        user.platformMfaRequired()),
                views);
    }
}
