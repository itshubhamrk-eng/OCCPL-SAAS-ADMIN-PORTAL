package com.occpl.platform.integration.internal;

import com.occpl.platform.catalog.api.ProductSummary;
import com.occpl.platform.entitlement.api.EntitlementStatus;
import com.occpl.platform.entitlement.api.Entitlements;
import com.occpl.platform.entitlement.api.Entitlements.AccessDecision;
import com.occpl.platform.identity.api.UserDirectory;
import com.occpl.platform.identity.api.UserStatus;
import com.occpl.platform.identity.api.UserSummary;
import com.occpl.platform.membership.api.MembershipDirectory;
import com.occpl.platform.membership.api.MembershipDirectory.ActiveMember;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "may this user use this product (feature) for this company right now?" for product
 * services, from current data: user status, ACTIVE membership with {@code company.products.launch},
 * company status and the entitlement (SECURITY.md §2).
 */
@Service
public class ServiceAccessService {

    static final String LAUNCH_PERMISSION = "company.products.launch";

    private final Entitlements entitlements;
    private final UserDirectory users;
    private final MembershipDirectory memberships;

    ServiceAccessService(Entitlements entitlements, UserDirectory users, MembershipDirectory memberships) {
        this.entitlements = entitlements;
        this.users = users;
        this.memberships = memberships;
    }

    /** Reasons extend the entitlement reasons with user/membership outcomes. */
    public enum Reason {
        OK, COMPANY_INACTIVE, NO_ENTITLEMENT, SUSPENDED, FEATURE_NOT_INCLUDED, LIMIT_REACHED,
        UNKNOWN_USER, USER_INACTIVE, NOT_A_MEMBER, NOT_PERMITTED
    }

    /**
     * @param userId the platform user, when a subject was given and is known
     * @param roles the user's company roles (empty without a user)
     */
    public record AccessCheck(boolean allowed, Reason reason, String productCode, UUID companyId, UUID userId,
            Set<String> roles, EntitlementStatus entitlementStatus, Long limit, Long used, Long remaining) {
    }

    @Transactional(readOnly = true)
    public AccessCheck check(ProductSummary product, UUID companyId, String userSubject, String featureCode) {
        UUID userId = null;
        Set<String> roles = Set.of();
        // Entitlement first: a product learns nothing about the members of companies that do not use it.
        AccessDecision decision = entitlements.check(companyId, product.code(), featureCode);
        if (decision.reason() == Entitlements.Reason.COMPANY_INACTIVE
                || decision.reason() == Entitlements.Reason.NO_ENTITLEMENT) {
            return denied(Reason.valueOf(decision.reason().name()), product, companyId, null, roles);
        }
        if (userSubject != null && !userSubject.isBlank()) {
            Optional<UserSummary> user = users.findBySubject(userSubject);
            if (user.isEmpty()) {
                return denied(Reason.UNKNOWN_USER, product, companyId, null, roles);
            }
            userId = user.get().id();
            if (user.get().status() != UserStatus.ACTIVE) {
                return denied(Reason.USER_INACTIVE, product, companyId, userId, roles);
            }
            Optional<ActiveMember> member = memberships.activeMember(companyId, userId);
            if (member.isEmpty()) {
                return denied(Reason.NOT_A_MEMBER, product, companyId, userId, roles);
            }
            roles = member.get().roleCodes();
            if (!member.get().permissions().contains(LAUNCH_PERMISSION)) {
                return denied(Reason.NOT_PERMITTED, product, companyId, userId, roles);
            }
        }
        return new AccessCheck(decision.allowed(), Reason.valueOf(decision.reason().name()), product.code(),
                companyId, userId, roles, decision.status(), decision.limit(), decision.used(), decision.remaining());
    }

    private static AccessCheck denied(Reason reason, ProductSummary product, UUID companyId, UUID userId,
            Set<String> roles) {
        return new AccessCheck(false, reason, product.code(), companyId, userId, roles, null, null, null, null);
    }
}
