package com.occpl.platform.membership.internal;

import com.occpl.platform.access.api.CompanyAccess;
import com.occpl.platform.access.api.MembershipAccess;
import com.occpl.platform.access.api.PlatformRoles;
import com.occpl.platform.access.api.RoleView;
import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.company.api.CompanySummary;
import com.occpl.platform.membership.api.MembershipStatus;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Member listing, lifecycle and role assignment within a company. */
@Service
public class MembershipService {

    private final CompanyMembershipRepository memberships;
    private final MembershipQueries queries;
    private final CompanyAccess companyAccess;
    private final CompanyDirectory companies;
    private final MembershipGuards guards;
    private final InvitationRepository invitations;
    private final AuditLogger audit;
    private final Clock clock;

    MembershipService(CompanyMembershipRepository memberships, MembershipQueries queries, CompanyAccess companyAccess,
            CompanyDirectory companies, MembershipGuards guards, InvitationRepository invitations, AuditLogger audit,
            Clock clock) {
        this.memberships = memberships;
        this.queries = queries;
        this.companyAccess = companyAccess;
        this.companies = companies;
        this.guards = guards;
        this.invitations = invitations;
        this.audit = audit;
        this.clock = clock;
    }

    public record MemberView(UUID membershipId, UUID userId, String email, String displayName,
            MembershipStatus status, Set<String> roles, Instant joinedAt, Instant createdAt) {
    }

    public record MemberPage(List<MemberView> items, int page, int size, long totalItems, int totalPages) {
    }

    public record UserMembershipView(UUID membershipId, UUID companyId, String companyCode, String companyName,
            String companyStatus, MembershipStatus status, Set<String> roles, Instant joinedAt) {
    }

    public enum Action { SUSPEND, REACTIVATE, DEACTIVATE }

    @Transactional(readOnly = true)
    public MemberPage list(UUID companyId, MembershipStatus status, String role, String q, Integer page, Integer size,
            List<String> sort) {
        MembershipQueries.MemberPage result = queries.search(companyId, status, role, q, page, size, sort);
        Map<UUID, MembershipAccess> access = companyAccess.forMemberships(
                result.rows().stream().map(MembershipQueries.MemberRow::membershipId).toList());
        List<MemberView> items = result.rows().stream().map(r -> new MemberView(r.membershipId(), r.userId(),
                r.email(), r.displayName(), r.status(),
                new TreeSet<>(access.getOrDefault(r.membershipId(), MembershipAccess.NONE).roleCodes()),
                r.joinedAt(), r.createdAt())).toList();
        int totalPages = (int) ((result.total() + result.size() - 1) / result.size());
        return new MemberPage(items, result.page(), result.size(), result.total(), totalPages);
    }

    @Transactional(readOnly = true)
    public MemberView get(UUID companyId, UUID membershipId) {
        CompanyMembership membership = memberships.findByIdAndCompanyId(membershipId, companyId)
                .orElseThrow(() -> ApiException.notFound("Membership"));
        return view(membership);
    }

    @Transactional(readOnly = true)
    public List<RoleView> assignableRoles(UUID companyId) {
        return companyAccess.assignableRoles(companyId);
    }

    @Transactional
    public MemberView changeStatus(UUID companyId, UUID membershipId, Action action, String reason) {
        CompanyMembership membership = memberships.lockByIdAndCompanyId(membershipId, companyId)
                .orElseThrow(() -> ApiException.notFound("Membership"));
        guards.requireNotSelf(membership);
        guards.requireWithinOwnPermissions(access(membershipId).permissions(), Set.of());
        if (action != Action.REACTIVATE) {
            guards.requireNotLastAdmin(companyId, membershipId);
        } else {
            guards.requireFreeSeat(companyId);
        }
        Instant now = clock.instant();
        MembershipStatus previous = membership.status();
        switch (action) {
            case SUSPEND -> membership.suspend(now);
            case REACTIVATE -> membership.reactivate(now);
            case DEACTIVATE -> {
                membership.deactivate(now);
                // A pending invitation must not bring the seat back later (accept or re-send).
                invitations.findFirstByMembershipIdAndStatus(membershipId, Invitation.Status.PENDING)
                        .ifPresent(invitation -> invitation.revoke(now));
            }
        }
        memberships.flush();
        Map<String, Object> details = new HashMap<>();
        details.put("from", previous.name());
        details.put("to", membership.status().name());
        if (reason != null) {
            details.put("reason", reason);
        }
        audit.record(AuditEvent.success("membership." + action.name().toLowerCase(), "membership", membershipId,
                companyId, details));
        return view(membership);
    }

    @Transactional
    public MemberView replaceRoles(UUID companyId, UUID membershipId, Set<String> roleCodes) {
        CompanyMembership membership = memberships.lockByIdAndCompanyId(membershipId, companyId)
                .orElseThrow(() -> ApiException.notFound("Membership"));
        if (membership.status() == MembershipStatus.DEACTIVATED) {
            throw ApiException.validation("Roles cannot be assigned to a deactivated membership.");
        }
        guards.requireNotSelf(membership);
        MembershipAccess current = access(membershipId);
        guards.requireWithinOwnPermissions(current.permissions(), companyAccess.permissionsOf(companyId, roleCodes));
        if (current.roleCodes().contains(PlatformRoles.COMPANY_ADMIN)
                && !roleCodes.contains(PlatformRoles.COMPANY_ADMIN)) {
            guards.requireNotLastAdmin(companyId, membershipId);
        }
        MembershipAccess updated = companyAccess.replaceMembershipRoles(companyId, membershipId, roleCodes,
                Actor.current().map(Actor::userId).orElse(null));
        audit.record(AuditEvent.success("membership.roles_changed", "membership", membershipId, companyId,
                Map.of("from", new TreeSet<>(current.roleCodes()), "to", new TreeSet<>(updated.roleCodes()))));
        return view(membership);
    }

    @Transactional(readOnly = true)
    public List<UserMembershipView> membershipsOfUser(UUID userId) {
        List<CompanyMembership> all = memberships.findByUserIdOrderByCreatedAt(userId);
        Map<UUID, CompanySummary> companyById = companies.findAll(all.stream().map(CompanyMembership::companyId).toList());
        Map<UUID, MembershipAccess> access = companyAccess.forMemberships(all.stream().map(CompanyMembership::id).toList());
        return all.stream().filter(m -> companyById.containsKey(m.companyId())).map(m -> {
            CompanySummary c = companyById.get(m.companyId());
            return new UserMembershipView(m.id(), c.id(), c.code(), c.displayName(), c.status().name(), m.status(),
                    new TreeSet<>(access.getOrDefault(m.id(), MembershipAccess.NONE).roleCodes()), m.joinedAt());
        }).toList();
    }

    private MembershipAccess access(UUID membershipId) {
        return companyAccess.forMemberships(Set.of(membershipId)).getOrDefault(membershipId, MembershipAccess.NONE);
    }

    private MemberView view(CompanyMembership m) {
        MembershipQueries.MemberRow row = queries.member(m.companyId(), m.id()).orElse(null);
        return new MemberView(m.id(), m.userId(), row == null ? null : row.email(),
                row == null ? null : row.displayName(), m.status(), new TreeSet<>(access(m.id()).roleCodes()),
                m.joinedAt(), m.createdAt());
    }
}
