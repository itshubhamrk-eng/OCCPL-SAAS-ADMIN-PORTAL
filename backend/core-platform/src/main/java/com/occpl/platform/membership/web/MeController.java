package com.occpl.platform.membership.web;

import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.membership.internal.MeService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final MeService meService;

    MeController(MeService meService) {
        this.meService = meService;
    }

    @GetMapping
    @Operation(summary = "Current user, platform access and company memberships",
            description = "Used by the portal for navigation. Every API still enforces permissions server-side.")
    MeService.MeView me() {
        return meService.me(CurrentUser.require());
    }
}
