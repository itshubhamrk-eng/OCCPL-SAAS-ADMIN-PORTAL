package com.occpl.platform.membership.web;

import com.occpl.platform.membership.internal.InvitationService;
import com.occpl.platform.membership.internal.InvitationService.InvitationView;
import com.occpl.platform.shared.paging.PageResponse;
import com.occpl.platform.shared.paging.SortParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/invitations")
class InvitationController {

    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping
    @PreAuthorize("@tenant.check(#companyId, 'company.users.manage', 'platform.companies.manage')")
    @Operation(summary = "Invite a user",
            description = "New users receive an account-setup email; users with an account receive an invitation email.")
    ResponseEntity<InvitationView> invite(@PathVariable UUID companyId,
            @Valid @RequestBody MembershipRequests.Invite request) {
        InvitationView view = invitations.invite(companyId, request.email(), request.roles(), request.firstName(),
                request.lastName());
        return ResponseEntity.created(URI.create("/api/v1/companies/" + companyId + "/invitations/" + view.id()))
                .body(view);
    }

    @GetMapping
    @PreAuthorize("@tenant.check(#companyId, 'company.users.read', 'platform.companies.read')")
    PageResponse<InvitationView> list(@PathVariable UUID companyId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @Parameter(description = "Repeatable, e.g. sort=createdAt,desc") SortParams sort) {
        return PageResponse.of(invitations.list(companyId, status, page, size, sort.values()), v -> v);
    }

    @PostMapping("/{invitationId}/resend")
    @PreAuthorize("@tenant.check(#companyId, 'company.users.manage', 'platform.companies.manage')")
    @Operation(summary = "Re-send an invitation", description = "Issues a new link (the old one stops working) and extends the expiry.")
    InvitationView resend(@PathVariable UUID companyId, @PathVariable UUID invitationId) {
        return invitations.resend(companyId, invitationId);
    }

    @PostMapping("/{invitationId}/revoke")
    @PreAuthorize("@tenant.check(#companyId, 'company.users.manage', 'platform.companies.manage')")
    InvitationView revoke(@PathVariable UUID companyId, @PathVariable UUID invitationId) {
        return invitations.revoke(companyId, invitationId);
    }
}
