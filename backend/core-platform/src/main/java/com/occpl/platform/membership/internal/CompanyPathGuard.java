package com.occpl.platform.membership.internal;

import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.company.api.CompanyStatus;
import com.occpl.platform.company.api.CompanySummary;
import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.membership.api.MembershipStatus;
import com.occpl.platform.shared.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Deny-by-default for every {@code /api/v1/companies/{companyId}/**} request (SECURITY.md §3): the
 * caller must hold an ACTIVE membership of a company that is not deactivated, or be platform staff
 * with a platform permission (MFA honoured) acting on an existing company. Anything else is 404.
 *
 * <p>It runs before the request body is read, so another company's endpoints answer the same 404
 * whatever is sent, and it protects company endpoints even if one were added without a
 * {@code @tenant.check}. The fine-grained permission check stays in {@link TenantAuthorization},
 * which reuses the membership looked up here. Registered in {@link MembershipConfiguration}.
 */
class CompanyPathGuard implements HandlerInterceptor {

    private static final String ATTRIBUTE = CompanyPathGuard.class.getName() + ".verified";

    /** Membership (null for platform staff) and company verified for this request. */
    record Verified(UUID companyId, CompanyMembership membership, CompanySummary company) {
    }

    private final CompanyMembershipRepository memberships;
    private final CompanyDirectory companies;

    CompanyPathGuard(CompanyMembershipRepository memberships, CompanyDirectory companies) {
        this.memberships = memberships;
        this.companies = companies;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String raw = variables == null ? null : variables.get("companyId");
        if (raw == null) {
            return true; // no company endpoint matched (404 or 405 follows)
        }
        UUID companyId;
        try {
            companyId = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Company");
        }
        CurrentUser user = CurrentUser.require();
        if (!user.platformPermissions().isEmpty()) {
            CompanySummary company = companies.find(companyId).orElseThrow(() -> ApiException.notFound("Company"));
            request.setAttribute(ATTRIBUTE, new Verified(companyId, null, company));
            return true;
        }
        CompanyMembership membership = memberships.findByCompanyIdAndUserId(companyId, user.userId())
                .filter(m -> m.status() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("Company"));
        CompanySummary company = companies.find(companyId)
                .filter(c -> c.status() != CompanyStatus.DEACTIVATED)
                .orElseThrow(() -> ApiException.notFound("Company"));
        request.setAttribute(ATTRIBUTE, new Verified(companyId, membership, company));
        return true;
    }

    /** What this request's guard verified for {@code companyId}, if it ran. */
    static Optional<Verified> verified(UUID companyId) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object value = attributes == null ? null : attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof Verified v && v.companyId().equals(companyId) ? Optional.of(v) : Optional.empty();
    }
}
