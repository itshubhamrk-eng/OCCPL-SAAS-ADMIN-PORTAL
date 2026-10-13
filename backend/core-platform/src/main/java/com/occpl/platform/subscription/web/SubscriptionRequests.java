package com.occpl.platform.subscription.web;

import com.occpl.platform.subscription.api.BillingPeriod;
import com.occpl.platform.subscription.internal.PlanService;
import com.occpl.platform.subscription.internal.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

final class SubscriptionRequests {

    static final String PLAN_CODE = "[a-z][a-z0-9-]{1,63}";

    private SubscriptionRequests() {
    }

    record Price(@NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency, @NotNull @Min(0) Long amountMinor) {
    }

    record Feature(@NotBlank @Size(max = 64) String code, Boolean enabled, @Min(0) Long limit) {
    }

    record Product(@NotNull UUID productId, @NotNull @Size(max = 100) List<@Valid Feature> features) {
    }

    record PlanBody(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotNull BillingPeriod billingPeriod,
            @NotNull @Min(0) @Max(365) Integer trialDays,
            @NotNull @Size(max = 20) List<@Valid Price> prices,
            @NotNull @Size(max = 50) List<@Valid Product> products) {

        PlanService.PlanContent toContent() {
            return new PlanService.PlanContent(name.strip(), description, billingPeriod, trialDays,
                    prices.stream().map(p -> new PlanService.Price(p.currency(), p.amountMinor())).toList(),
                    products.stream().map(p -> new PlanService.ProductContent(p.productId(), p.features().stream()
                            .map(f -> new PlanService.FeatureContent(f.code(), f.enabled(), f.limit())).toList())).toList());
        }
    }

    record CreatePlan(
            @NotBlank @Pattern(regexp = PLAN_CODE, message = "lower-case letters, digits and hyphens; 2-64 characters") String code,
            @NotNull @Valid PlanBody plan) {
    }

    record CopyPlan(@NotBlank @Pattern(regexp = PLAN_CODE) String code, @Size(min = 1, max = 200) String name) {
    }

    record Assign(@NotNull UUID planId, @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency, boolean startTrial) {
    }

    record ChangePlan(@NotNull UUID planId) {
    }

    record Cancel(@NotNull SubscriptionService.CancelMode mode, @Size(max = 500) String reason) {
    }

    record Reason(@Size(max = 500) String reason) {
    }
}
