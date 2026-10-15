package com.occpl.platform.subscription.web;

import com.occpl.platform.shared.paging.PageResponse;
import com.occpl.platform.shared.paging.SortParams;
import com.occpl.platform.subscription.api.SubscriptionStatus;
import com.occpl.platform.subscription.internal.SubscriptionService;
import com.occpl.platform.subscription.internal.SubscriptionService.SubscriptionView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SubscriptionController {

    private static final String BASE = "/api/v1/companies/{companyId}/subscriptions";

    private final SubscriptionService subscriptions;

    SubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @PostMapping(BASE)
    @PreAuthorize("hasAuthority('platform.subscriptions.manage')")
    @Operation(summary = "Assign a plan to a company", description = "startTrial uses the plan's trial days; otherwise the first period starts now.")
    ResponseEntity<SubscriptionView> assign(@PathVariable UUID companyId,
            @Valid @RequestBody SubscriptionRequests.Assign request) {
        SubscriptionView view = subscriptions.assign(companyId, request.planId(), request.currency(), request.startTrial());
        return ResponseEntity.created(URI.create("/api/v1/companies/" + companyId + "/subscriptions/" + view.id()))
                .body(view);
    }

    @GetMapping(BASE)
    @PreAuthorize("@tenant.check(#companyId, 'company.subscription.read', 'platform.subscriptions.read')")
    List<SubscriptionView> list(@PathVariable UUID companyId) {
        return subscriptions.forCompany(companyId);
    }

    @GetMapping(BASE + "/{subscriptionId}")
    @PreAuthorize("@tenant.check(#companyId, 'company.subscription.read', 'platform.subscriptions.read')")
    SubscriptionView get(@PathVariable UUID companyId, @PathVariable UUID subscriptionId) {
        return subscriptions.get(companyId, subscriptionId);
    }

    @PostMapping(BASE + "/{subscriptionId}/activate")
    @PreAuthorize("hasAuthority('platform.subscriptions.manage')")
    @Operation(summary = "Convert a trial into a paid subscription")
    SubscriptionView activate(@PathVariable UUID companyId, @PathVariable UUID subscriptionId) {
        return subscriptions.activate(companyId, subscriptionId);
    }

    @PostMapping(BASE + "/{subscriptionId}/renew")
    @PreAuthorize("hasAuthority('platform.subscriptions.manage')")
    @Operation(summary = "Record payment for the next period",
            description = "ACTIVE/PAST_DUE/SUSPENDED → ACTIVE; applies a scheduled downgrade and clears a scheduled cancellation.")
    SubscriptionView renew(@PathVariable UUID companyId, @PathVariable UUID subscriptionId) {
        return subscriptions.renew(companyId, subscriptionId);
    }

    @PostMapping(BASE + "/{subscriptionId}/change-plan")
    @PreAuthorize("hasAuthority('platform.subscriptions.manage')")
    @Operation(summary = "Change plan", description = "Upgrade (price ≥ current) applies now; downgrade at the end of the period.")
    SubscriptionView changePlan(@PathVariable UUID companyId, @PathVariable UUID subscriptionId,
            @Valid @RequestBody SubscriptionRequests.ChangePlan request) {
        return subscriptions.changePlan(companyId, subscriptionId, request.planId());
    }

    @PostMapping(BASE + "/{subscriptionId}/cancel")
    @PreAuthorize("hasAuthority('platform.subscriptions.manage')")
    @Operation(summary = "Cancel now (IMMEDIATE) or at the end of the period (PERIOD_END)")
    SubscriptionView cancel(@PathVariable UUID companyId, @PathVariable UUID subscriptionId,
            @Valid @RequestBody SubscriptionRequests.Cancel request) {
        return subscriptions.cancel(companyId, subscriptionId, request.mode(), request.reason());
    }

    @PostMapping(BASE + "/{subscriptionId}/resume")
    @PreAuthorize("hasAuthority('platform.subscriptions.manage')")
    @Operation(summary = "Revoke a scheduled cancellation")
    SubscriptionView resume(@PathVariable UUID companyId, @PathVariable UUID subscriptionId) {
        return subscriptions.resume(companyId, subscriptionId);
    }

    @PostMapping(BASE + "/{subscriptionId}/suspend")
    @PreAuthorize("hasAuthority('platform.subscriptions.manage')")
    SubscriptionView suspend(@PathVariable UUID companyId, @PathVariable UUID subscriptionId,
            @Valid @RequestBody(required = false) SubscriptionRequests.Reason request) {
        return subscriptions.suspend(companyId, subscriptionId, request == null ? null : request.reason());
    }

    @GetMapping("/api/v1/platform/subscriptions")
    @PreAuthorize("hasAuthority('platform.subscriptions.read')")
    PageResponse<SubscriptionView> search(@RequestParam(required = false) SubscriptionStatus status,
            @RequestParam(required = false) UUID companyId, @RequestParam(required = false) UUID planId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @Parameter(description = "Repeatable, e.g. sort=currentPeriodEnd,asc") SortParams sort) {
        return PageResponse.of(subscriptions.search(status, companyId, planId, page, size, sort.values()), v -> v);
    }
}
