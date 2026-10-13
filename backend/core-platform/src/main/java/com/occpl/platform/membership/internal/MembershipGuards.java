package com.occpl.platform.membership.internal;

import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.membership.api.TenantContext;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Business rules protecting company administration (SECURITY.md §2):
 * <ul>
 *   <li><b>No escalation:</b> a member may only grant, change or remove access that is within their own
 *       permissions (a manager cannot create, demote or suspend an admin). Platform staff acting through a
 *       platform permission are not restricted.</li>
 *   <li><b>No self-changes:</b> members cannot change their own membership, avoiding accidental lock-out.</li>
 *   <li><b>Last admin:</b> a company keeps at least one ACTIVE Company Admin.</li>
 * </ul>
 */
@Component
class MembershipGuards {

    private final MembershipQueries queries;

    MembershipGuards(MembershipQueries queries) {
        this.queries = queries;
    }

    /** Throws unless the caller may grant {@code newPermissions} to someone currently holding {@code currentPermissions}. */
    void requireWithinOwnPermissions(Set<String> currentPermissions, Set<String> newPermissions) {
        TenantContext context = TenantContext.current().orElse(null);
        if (context == null || context.membershipId() == null) {
            return; // platform staff (authorised by a platform permission) or an internal system flow
        }
        if (!context.permissions().containsAll(currentPermissions) || !context.permissions().containsAll(newPermissions)) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "You can only grant or change access that is within your own permissions.");
        }
    }

    void requireNotSelf(CompanyMembership target) {
        if (target.userId().equals(CurrentUser.require().userId())) {
            throw new ApiException(ErrorCode.CONFLICT, "You cannot change your own membership.");
        }
    }

    /** Call before an operation that removes {@code target}'s active admin rights. */
    void requireNotLastAdmin(UUID companyId, UUID targetMembershipId) {
        List<UUID> admins = queries.lockActiveAdmins(companyId);
        if (admins.contains(targetMembershipId) && admins.size() <= 1) {
            throw new ApiException(ErrorCode.LAST_ADMIN, "The company must keep at least one active Company Admin.");
        }
    }
}
