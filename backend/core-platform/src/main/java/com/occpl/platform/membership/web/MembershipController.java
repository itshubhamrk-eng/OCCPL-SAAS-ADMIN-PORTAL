package com.occpl.platform.membership.web;

import com.occpl.platform.access.api.RoleView;
import com.occpl.platform.membership.api.MembershipStatus;
import com.occpl.platform.membership.internal.MembershipService;
import com.occpl.platform.membership.internal.MembershipService.MemberView;
import com.occpl.platform.shared.paging.SortParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies/{companyId}")
class MembershipController {

    private final MembershipService memberships;

    MembershipController(MembershipService memberships) {
        this.memberships = memberships;
    }

    @GetMapping("/memberships")
    @PreAuthorize("@tenant.check(#companyId, 'company.users.read', 'platform.companies.read')")
    @Operation(summary = "List members", description = "Filter by status or role; q matches the start of email or name.")
    MembershipService.MemberPage list(@PathVariable UUID companyId,
            @RequestParam(required = false) MembershipStatus status, @RequestParam(required = false) String role,
            @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @Parameter(description = "Repeatable, e.g. sort=createdAt,desc") SortParams sort) {
        return memberships.list(companyId, status, role, q, page, size, sort.values());
    }

    @GetMapping("/memberships/{membershipId}")
    @PreAuthorize("@tenant.check(#companyId, 'company.users.read', 'platform.companies.read')")
    MemberView get(@PathVariable UUID companyId, @PathVariable UUID membershipId) {
        return memberships.get(companyId, membershipId);
    }

    @GetMapping("/roles")
    @PreAuthorize("@tenant.check(#companyId, 'company.users.read', 'platform.companies.read')")
    @Operation(summary = "Roles that can be assigned in this company, with their permissions")
    List<RoleView> roles(@PathVariable UUID companyId) {
        return memberships.assignableRoles(companyId);
    }

    @PostMapping("/memberships/{membershipId}/suspend")
    @PreAuthorize("@tenant.check(#companyId, 'company.users.manage', 'platform.companies.manage')")
    MemberView suspend(@PathVariable UUID companyId, @PathVariable UUID membershipId,
            @Valid @RequestBody(required = false) MembershipRequests.Reason request) {
        return memberships.changeStatus(companyId, membershipId, MembershipService.Action.SUSPEND, reason(request));
    }

    @PostMapping("/memberships/{membershipId}/reactivate")
    @PreAuthorize("@tenant.check(#companyId, 'company.users.manage', 'platform.companies.manage')")
    MemberView reactivate(@PathVariable UUID companyId, @PathVariable UUID membershipId,
            @Valid @RequestBody(required = false) MembershipRequests.Reason request) {
        return memberships.changeStatus(companyId, membershipId, MembershipService.Action.REACTIVATE, reason(request));
    }

    @PostMapping("/memberships/{membershipId}/deactivate")
    @PreAuthorize("@tenant.check(#companyId, 'company.users.manage', 'platform.companies.manage')")
    MemberView deactivate(@PathVariable UUID companyId, @PathVariable UUID membershipId,
            @Valid @RequestBody(required = false) MembershipRequests.Reason request) {
        return memberships.changeStatus(companyId, membershipId, MembershipService.Action.DEACTIVATE, reason(request));
    }

    @PutMapping("/memberships/{membershipId}/roles")
    @PreAuthorize("@tenant.check(#companyId, 'company.roles.assign', 'platform.companies.manage')")
    @Operation(summary = "Replace a member's roles",
            description = "You can only grant roles within your own permissions; the last Company Admin cannot be demoted.")
    MemberView replaceRoles(@PathVariable UUID companyId, @PathVariable UUID membershipId,
            @Valid @RequestBody MembershipRequests.Roles request) {
        return memberships.replaceRoles(companyId, membershipId, request.roles());
    }

    private static String reason(MembershipRequests.Reason request) {
        return request == null ? null : request.reason();
    }
}
