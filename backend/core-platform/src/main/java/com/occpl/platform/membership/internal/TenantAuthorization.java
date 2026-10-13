package com.occpl.platform.membership.internal;

import com.occpl.platform.access.api.CompanyAccess;
import com.occpl.platform.access.api.MembershipAccess;
import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.company.api.CompanyStatus;
import com.occpl.platform.company.api.CompanySummary;
import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.membership.api.MembershipStatus;
import com.occpl.platform.membership.api.TenantContext;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company-scoped authorization for {@code @PreAuthorize}, e.g.
 * {@code @PreAuthorize("@tenant.check(#companyId, 'company.users.read', 'platform.companies.read')")}.
 *
 * <p>The company id always comes from the request path and is verified against the caller's own
 * membership (SECURITY.md §3):
 * <ul>
 *   <li>no ACTIVE membership, unknown or deactivated company → 404, so company ids cannot be probed;</li>
 *   <li>membership without the permission → 403 ({@code false});</li>
 *   <li>suspended company → only {@code *.read} permissions are allowed;</li>
 *   <li>the optional platform permission lets platform staff act on any existing company.</li>
 * </ul>
 */
@Component("tenant")
public class TenantAuthorization {

    private final CompanyMembershipRepository memberships;
    private final CompanyDirectory companies;
    private final CompanyAccess companyAccess;

    TenantAuthorization(CompanyMembershipRepository memberships, CompanyDirectory companies,
            CompanyAccess companyAccess) {
        this.memberships = memberships;
        this.companies = companies;
        this.companyAccess = companyAccess;
    }

    @Transactional(readOnly = true)
    public boolean check(UUID companyId, String permission) {
        return check(companyId, permission, null);
    }

    @Transactional(readOnly = true)
    public boolean check(UUID companyId, String permission, String platformPermission) {
        if (companyId == null || permission == null) {
            throw ApiException.notFound("Company");
        }
        CurrentUser user = CurrentUser.require();

        if (platformPermission != null && user.hasPlatformPermission(platformPermission)) {
            companies.find(companyId).orElseThrow(() -> ApiException.notFound("Company"));
            TenantContext.set(new TenantContext(companyId, null, Set.of()));
            return true;
        }

        CompanyMembership membership = memberships.findByCompanyIdAndUserId(companyId, user.userId())
                .filter(m -> m.status() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("Company"));
        CompanySummary company = companies.find(companyId)
                .filter(c -> c.status() != CompanyStatus.DEACTIVATED)
                .orElseThrow(() -> ApiException.notFound("Company"));
        if ((company.status() == CompanyStatus.SUSPENDED || company.status() == CompanyStatus.PENDING)
                && !permission.endsWith(".read")) {
            throw new ApiException(ErrorCode.COMPANY_SUSPENDED,
                    "This company is not active; only read access is available.");
        }

        MembershipAccess access = companyAccess.forMemberships(Set.of(membership.id()))
                .getOrDefault(membership.id(), MembershipAccess.NONE);
        if (!access.permissions().contains(permission)) {
            return false;
        }
        TenantContext.set(new TenantContext(companyId, membership.id(), access.permissions()));
        return true;
    }
}
