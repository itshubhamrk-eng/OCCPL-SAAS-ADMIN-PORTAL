package com.occpl.platform.entitlement.web;

import com.occpl.platform.entitlement.internal.EntitlementService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EntitlementController {

    private final EntitlementService entitlements;

    EntitlementController(EntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    @GetMapping("/api/v1/companies/{companyId}/entitlements")
    @PreAuthorize("@tenant.check(#companyId, 'company.usage.read', 'platform.subscriptions.read')")
    @Operation(summary = "Products the company may use, with feature limits and current usage")
    List<EntitlementService.ProductEntitlementView> forCompany(@PathVariable UUID companyId) {
        return entitlements.forCompany(companyId);
    }
}
