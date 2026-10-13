package com.occpl.platform.membership.internal;

import com.occpl.platform.access.api.CompanyAccess;
import com.occpl.platform.access.api.MembershipAccess;
import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.company.api.CompanyStatus;
import com.occpl.platform.company.api.CompanySummary;
import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.identity.api.UserDirectory;
import com.occpl.platform.identity.api.UserSummary;
import com.occpl.platform.membership.api.MembershipStatus;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.id.UuidV7;
import com.occpl.platform.shared.paging.PageRequests;
import com.occpl.platform.shared.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Invitations: issue, re-send, revoke, accept and expire. Endpoint authorization is done by controllers. */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);
    private static final Map<String, String> SORTS = Map.of("createdAt", "createdAt", "expiresAt", "expiresAt",
            "email", "email", "status", "status");

    private final InvitationRepository invitations;
    private final CompanyMembershipRepository memberships;
    private final CompanyDirectory companies;
    private final CompanyAccess companyAccess;
    private final UserDirectory users;
    private final MembershipGuards guards;
    private final AuditLogger audit;
    private final ApplicationEventPublisher events;
    private final InvitationProperties properties;
    private final Clock clock;

    InvitationService(InvitationRepository invitations, CompanyMembershipRepository memberships,
            CompanyDirectory companies, CompanyAccess companyAccess, UserDirectory users, MembershipGuards guards,
            AuditLogger audit, ApplicationEventPublisher events, InvitationProperties properties, Clock clock) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.companies = companies;
        this.companyAccess = companyAccess;
        this.users = users;
        this.guards = guards;
        this.audit = audit;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    public record InvitationView(UUID id, UUID membershipId, String email, String status, Set<String> roles,
            Instant expiresAt, UUID invitedBy, Instant createdAt, Instant acceptedAt) {
    }

    public record AcceptedView(UUID membershipId, UUID companyId, String companyCode, String companyName,
            Set<String> roles) {
    }

    @Transactional
    public InvitationView invite(UUID companyId, String email, Set<String> roleCodes, String firstName,
            String lastName) {
        String normalized = email.strip().toLowerCase();
        CompanySummary company = companies.find(companyId).orElseThrow(() -> ApiException.notFound("Company"));
        if (company.status() == CompanyStatus.DEACTIVATED) {
            throw new ApiException(ErrorCode.CONFLICT, "Members cannot be invited to a deactivated company.");
        }
        requireAllowedDomain(companyId, normalized);
        Set<String> permissions = companyAccess.permissionsOf(companyId, roleCodes);
        guards.requireWithinOwnPermissions(Set.of(), permissions);

        UserSummary user = users.findOrCreateInvited(normalized);
        Instant now = clock.instant();
        CompanyMembership membership = memberships.findByCompanyIdAndUserId(companyId, user.id()).orElse(null);
        if (membership == null || membership.status() == MembershipStatus.DEACTIVATED) {
            guards.requireFreeSeat(companyId);
        }
        if (membership == null) {
            membership = memberships.save(new CompanyMembership(UuidV7.generate(clock), companyId, user.id(), now));
        } else {
            switch (membership.status()) {
                case ACTIVE -> throw new ApiException(ErrorCode.DUPLICATE_MEMBERSHIP,
                        "This user is already a member of the company.");
                case SUSPENDED -> throw new ApiException(ErrorCode.CONFLICT,
                        "This user's membership is suspended; reactivate it instead.");
                case INVITED, DEACTIVATED -> {
                    if (invitations.findFirstByMembershipIdAndStatus(membership.id(), Invitation.Status.PENDING)
                            .filter(i -> !i.isExpired(now)).isPresent()) {
                        throw new ApiException(ErrorCode.INVITATION_PENDING,
                                "An invitation is already pending for this email; re-send it instead.");
                    }
                    invitations.findFirstByMembershipIdAndStatus(membership.id(), Invitation.Status.PENDING)
                            .ifPresent(stale -> stale.expire(now));
                    membership.reinvite(now);
                }
            }
        }
        UUID actor = Actor.current().map(Actor::userId).orElse(null);
        companyAccess.replaceMembershipRoles(companyId, membership.id(), roleCodes, actor);

        String token = InvitationTokens.newToken();
        Invitation invitation = invitations.saveAndFlush(new Invitation(UuidV7.generate(clock), companyId,
                membership.id(), normalized, InvitationTokens.hash(token), now.plus(properties.validity()), actor, now));
        auditAction("membership.invited", invitation, Map.of("email", normalized, "roles", new TreeSet<>(roleCodes)));
        publishIssued(invitation, company, firstName, lastName, token);
        return view(invitation, roleCodes);
    }

    @Transactional
    public InvitationView resend(UUID companyId, UUID invitationId) {
        Invitation invitation = invitations.findByIdAndCompanyId(invitationId, companyId)
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        if (invitation.status() != Invitation.Status.PENDING && invitation.status() != Invitation.Status.EXPIRED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only pending or expired invitations can be re-sent.");
        }
        CompanyMembership membership = memberships.findById(invitation.membershipId()).orElseThrow();
        Set<String> roles = rolesOf(membership.id()).roleCodes();
        guards.requireWithinOwnPermissions(rolesOf(membership.id()).permissions(), Set.of());
        Instant now = clock.instant();
        if (membership.status() != MembershipStatus.INVITED) {
            membership.reinvite(now);
        }
        String token = InvitationTokens.newToken();
        invitation.reissue(InvitationTokens.hash(token), now.plus(properties.validity()),
                Actor.current().map(Actor::userId).orElse(null), now);
        invitations.flush();
        auditAction("membership.invitation_resent", invitation, Map.of("email", invitation.email()));
        CompanySummary company = companies.find(companyId).orElseThrow();
        publishIssued(invitation, company, null, null, token);
        return view(invitation, roles);
    }

    @Transactional
    public InvitationView revoke(UUID companyId, UUID invitationId) {
        Invitation invitation = invitations.findByIdAndCompanyId(invitationId, companyId)
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        if (invitation.status() != Invitation.Status.PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Only pending invitations can be revoked.");
        }
        CompanyMembership membership = memberships.findById(invitation.membershipId()).orElseThrow();
        MembershipAccess access = rolesOf(membership.id());
        guards.requireWithinOwnPermissions(access.permissions(), Set.of());
        Instant now = clock.instant();
        invitation.revoke(now);
        if (membership.status() == MembershipStatus.INVITED) {
            membership.deactivate(now);
        }
        auditAction("membership.invitation_revoked", invitation, Map.of("email", invitation.email()));
        return view(invitation, access.roleCodes());
    }

    /**
     * Accepts an invitation for the signed-in user. The token must belong to an invitation addressed
     * to this user (matched through their verified email at first login); anything else is reported
     * as not found so tokens and invitations cannot be probed.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AcceptedView accept(String token) {
        CurrentUser user = CurrentUser.require();
        Invitation invitation = invitations.findByTokenHash(InvitationTokens.hash(token))
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        CompanyMembership membership = memberships.findById(invitation.membershipId()).orElseThrow();
        CompanySummary company = companies.find(invitation.companyId()).orElseThrow();
        if (!membership.userId().equals(user.userId()) || invitation.status() == Invitation.Status.REVOKED
                || company.status() == CompanyStatus.DEACTIVATED) {
            throw ApiException.notFound("Invitation");
        }
        if (invitation.status() == Invitation.Status.ACCEPTED && membership.status() == MembershipStatus.ACTIVE) {
            return accepted(membership, company); // idempotent repeat
        }
        Instant now = clock.instant();
        if (invitation.isExpired(now) || invitation.status() != Invitation.Status.PENDING) {
            if (invitation.status() == Invitation.Status.PENDING) {
                invitation.expire(now);
            }
            throw new ApiException(ErrorCode.INVITATION_EXPIRED,
                    "This invitation has expired. Ask an administrator to send a new one.");
        }
        membership.accept(now);
        invitation.accept(now);
        audit.record(AuditEvent.success("membership.invitation_accepted", "membership", membership.id(),
                invitation.companyId(), Map.of("invitationId", invitation.id().toString())));
        return accepted(membership, company);
    }

    @Transactional(readOnly = true)
    public Page<InvitationView> list(UUID companyId, String status, Integer page, Integer size, List<String> sort) {
        Invitation.Status statusFilter;
        try {
            statusFilter = status == null || status.isBlank() ? null : Invitation.Status.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("status must be PENDING, ACCEPTED, REVOKED or EXPIRED.");
        }
        Page<Invitation> result = invitations.findByCompany(companyId, statusFilter,
                PageRequests.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, MembershipAccess> access = companyAccess.forMemberships(
                result.getContent().stream().map(Invitation::membershipId).toList());
        return result.map(i -> view(i, access.getOrDefault(i.membershipId(), MembershipAccess.NONE).roleCodes()));
    }

    /** Marks overdue invitations EXPIRED in bounded batches; safe to run concurrently on several instances. */
    @Transactional
    public int expireOverdue() {
        int total = 0;
        int updated;
        do {
            updated = invitations.expireOverdue(clock.instant(), 500);
            total += updated;
        } while (updated == 500);
        if (total > 0) {
            log.info("Expired {} invitations", total);
        }
        return total;
    }

    private void requireAllowedDomain(UUID companyId, String email) {
        List<String> allowed = companies.allowedEmailDomains(companyId);
        String domain = email.substring(email.indexOf('@') + 1);
        if (!allowed.isEmpty() && !allowed.contains(domain)) {
            throw ApiException.validation("This company only allows invitations to: " + String.join(", ", allowed) + ".");
        }
    }

    private void publishIssued(Invitation invitation, CompanySummary company, String firstName, String lastName,
            String token) {
        String inviter = Actor.current().flatMap(a -> users.find(a.userId()))
                .map(u -> u.displayName() != null ? u.displayName() : u.email()).orElse(null);
        events.publishEvent(new InvitationIssued(invitation.id(), company.id(), company.displayName(),
                invitation.email(), firstName, lastName, inviter, token, invitation.expiresAt()));
    }

    private void auditAction(String action, Invitation invitation, Map<String, Object> details) {
        AuditEvent event = AuditEvent.success(action, "invitation", invitation.id(), invitation.companyId(), details);
        if (Actor.current().isPresent()) {
            audit.record(event);
        } else {
            audit.recordSystem(event);
        }
    }

    private MembershipAccess rolesOf(UUID membershipId) {
        return companyAccess.forMemberships(Set.of(membershipId)).getOrDefault(membershipId, MembershipAccess.NONE);
    }

    private AcceptedView accepted(CompanyMembership membership, CompanySummary company) {
        return new AcceptedView(membership.id(), company.id(), company.code(), company.displayName(),
                new TreeSet<>(rolesOf(membership.id()).roleCodes()));
    }

    private static InvitationView view(Invitation i, Set<String> roles) {
        return new InvitationView(i.id(), i.membershipId(), i.email(), i.status().name(), new TreeSet<>(roles),
                i.expiresAt(), i.invitedBy(), i.createdAt(), i.acceptedAt());
    }
}
