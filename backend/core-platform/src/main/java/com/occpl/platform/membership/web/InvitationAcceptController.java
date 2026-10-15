package com.occpl.platform.membership.web;

import com.occpl.platform.membership.internal.InvitationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invitations")
class InvitationAcceptController {

    private final InvitationService invitations;

    InvitationAcceptController(InvitationService invitations) {
        this.invitations = invitations;
    }

    /** The token travels in the body, never the URL, so it does not appear in access logs. */
    @PostMapping("/accept")
    @Operation(summary = "Accept an invitation as the signed-in user",
            description = "404 if the token is unknown, revoked or addressed to someone else; 410 if expired.")
    InvitationService.AcceptedView accept(@Valid @RequestBody MembershipRequests.Accept request) {
        return invitations.accept(request.token());
    }
}
