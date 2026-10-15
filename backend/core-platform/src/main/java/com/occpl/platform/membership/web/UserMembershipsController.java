package com.occpl.platform.membership.web;

import com.occpl.platform.membership.internal.MembershipService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UserMembershipsController {

    private final MembershipService memberships;

    UserMembershipsController(MembershipService memberships) {
        this.memberships = memberships;
    }

    @GetMapping("/api/v1/users/{userId}/memberships")
    @PreAuthorize("hasAuthority('platform.users.read')")
    List<MembershipService.UserMembershipView> memberships(@PathVariable UUID userId) {
        return memberships.membershipsOfUser(userId);
    }
}
