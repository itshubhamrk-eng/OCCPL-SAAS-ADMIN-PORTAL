package com.occpl.platform.subscription.web;

import com.occpl.platform.shared.paging.PageResponse;
import com.occpl.platform.shared.paging.SortParams;
import com.occpl.platform.subscription.api.PlanStatus;
import com.occpl.platform.subscription.internal.PlanService;
import com.occpl.platform.subscription.internal.PlanService.PlanView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/plans")
class PlanController {

    private final PlanService plans;

    PlanController(PlanService plans) {
        this.plans = plans;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('platform.plans.manage')")
    @Operation(summary = "Create a draft plan")
    ResponseEntity<PlanView> create(@Valid @RequestBody SubscriptionRequests.CreatePlan request) {
        PlanView plan = plans.create(request.code(), request.plan().toContent());
        return ResponseEntity.created(URI.create("/api/v1/plans/" + plan.id())).body(plan);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.plans.read')")
    PageResponse<PlanView> search(@RequestParam(required = false) PlanStatus status,
            @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Repeatable, e.g. sort=name,asc") SortParams sort) {
        return PageResponse.of(plans.search(status, q, page, size, sort.values()), v -> v);
    }

    @GetMapping("/{planId}")
    @PreAuthorize("hasAuthority('platform.plans.read')")
    PlanView get(@PathVariable UUID planId) {
        return plans.get(planId);
    }

    @PutMapping("/{planId}")
    @PreAuthorize("hasAuthority('platform.plans.manage')")
    @Operation(summary = "Replace a draft plan's content", description = "409 for active or archived plans: copy them instead.")
    PlanView update(@PathVariable UUID planId, @Valid @RequestBody SubscriptionRequests.PlanBody request) {
        return plans.update(planId, request.toContent());
    }

    @PostMapping("/{planId}/publish")
    @PreAuthorize("hasAuthority('platform.plans.manage')")
    @Operation(summary = "Publish (freeze) a plan", description = "Needs a price and active products with active features.")
    PlanView publish(@PathVariable UUID planId) {
        return plans.publish(planId);
    }

    @PostMapping("/{planId}/archive")
    @PreAuthorize("hasAuthority('platform.plans.manage')")
    @Operation(summary = "Archive a plan", description = "Existing subscriptions continue; it can no longer be assigned.")
    PlanView archive(@PathVariable UUID planId) {
        return plans.archive(planId);
    }

    @PostMapping("/{planId}/copy")
    @PreAuthorize("hasAuthority('platform.plans.manage')")
    @Operation(summary = "Copy a plan into a new draft")
    ResponseEntity<PlanView> copy(@PathVariable UUID planId, @Valid @RequestBody SubscriptionRequests.CopyPlan request) {
        PlanView plan = plans.copy(planId, request.code(), request.name());
        return ResponseEntity.created(URI.create("/api/v1/plans/" + plan.id())).body(plan);
    }
}
