package com.occpl.platform.integration.web;

import com.occpl.platform.catalog.api.ProductSummary;
import com.occpl.platform.entitlement.api.Entitlements;
import com.occpl.platform.entitlement.api.Entitlements.UsageResult;
import com.occpl.platform.integration.internal.ServiceAccessService;
import com.occpl.platform.integration.internal.ServiceAccessService.AccessCheck;
import com.occpl.platform.integration.internal.ServiceCaller;
import com.occpl.platform.provisioning.api.ProvisioningCallbacks;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by product services with a client-credentials token (security chain: SERVICE authority
 * only). The product is the one registered with the token's client id.
 */
@RestController
@RequestMapping("/api/v1/service")
@Tag(name = "Service API", description = "For product services (client-credentials tokens)")
class ServiceApiController {

    private static final String FEATURE_CODE = "[a-z][a-z0-9_.-]{1,63}";

    private final ServiceCaller caller;
    private final ServiceAccessService access;
    private final Entitlements entitlements;
    private final ProvisioningCallbacks callbacks;

    ServiceApiController(ServiceCaller caller, ServiceAccessService access, Entitlements entitlements,
            ProvisioningCallbacks callbacks) {
        this.caller = caller;
        this.access = access;
        this.entitlements = entitlements;
        this.callbacks = callbacks;
    }

    record UsageRequest(@NotNull UUID companyId,
            @NotBlank @Pattern(regexp = FEATURE_CODE) String featureCode,
            @Positive @Max(1_000_000) long quantity,
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_.:-]+") String idempotencyKey) {
    }

    record UsageResponse(long used, Long limit, Long remaining, boolean replayed) {
        static UsageResponse of(UsageResult r) {
            return new UsageResponse(r.used(), r.limit(), r.remaining(), r.replayed());
        }
    }

    record CallbackRequest(@NotNull Outcome outcome, @Size(max = 1000) String message) {
        enum Outcome { SUCCEEDED, FAILED }
    }

    @GetMapping("/access")
    @Operation(summary = "Check a company's (and optionally a user's) access to the calling product or one of its features",
            description = "userSubject is the 'sub' claim of the user's token. Always 200; see 'allowed' and 'reason'.")
    AccessCheck check(@RequestParam UUID companyId, @RequestParam(required = false) @Size(max = 255) String userSubject,
            @RequestParam(required = false) @Pattern(regexp = FEATURE_CODE) String featureCode) {
        return access.check(caller.product(), companyId, userSubject, featureCode);
    }

    @PostMapping("/usage/consume")
    @Operation(summary = "Consume usage of a LIMIT feature atomically",
            description = "Idempotent per company and key: a repeated key returns the first result. 409 LIMIT_EXCEEDED when over the limit.")
    UsageResponse consume(@Valid @RequestBody UsageRequest request) {
        ProductSummary product = caller.product();
        return UsageResponse.of(entitlements.consume(request.companyId(), product.code(), request.featureCode(),
                request.quantity(), scopedKey(product, request.idempotencyKey())));
    }

    @PostMapping("/usage/release")
    @Operation(summary = "Give back stock usage (e.g. records deleted)", description = "Idempotent like consume.")
    UsageResponse release(@Valid @RequestBody UsageRequest request) {
        ProductSummary product = caller.product();
        return UsageResponse.of(entitlements.release(request.companyId(), product.code(), request.featureCode(),
                request.quantity(), scopedKey(product, request.idempotencyKey())));
    }

    @PostMapping("/provisioning/jobs/{jobId}/callback")
    @Operation(summary = "Report completion of an asynchronous provisioning job")
    ResponseEntity<Void> callback(@PathVariable UUID jobId, @Valid @RequestBody CallbackRequest request) {
        callbacks.complete(caller.product().id(), jobId, request.outcome() == CallbackRequest.Outcome.SUCCEEDED,
                request.message());
        return ResponseEntity.noContent().build();
    }

    /** Keys are unique per company; prefixing the product keeps two products' keys apart. */
    private static String scopedKey(ProductSummary product, String key) {
        return product.code() + ":" + key;
    }
}
