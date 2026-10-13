package com.occpl.platform.catalog.web;

import com.occpl.platform.catalog.api.FeatureStatus;
import com.occpl.platform.catalog.internal.CatalogService;
import com.occpl.platform.catalog.internal.CatalogService.FeatureView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products/{productId}/features")
class ProductFeatureController {

    private final CatalogService catalog;

    ProductFeatureController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('platform.products.manage')")
    @Operation(summary = "Add a feature",
            description = "BOOLEAN: on/off. LIMIT: needs unit and resetPeriod (stock units such as SEATS must use NONE). Code and type are immutable.")
    ResponseEntity<FeatureView> add(@PathVariable UUID productId, @Valid @RequestBody CatalogRequests.NewFeature request) {
        FeatureView feature = catalog.addFeature(productId, request.code(), request.name().strip(), request.description(),
                request.type(), request.unit(), request.resetPeriod());
        return ResponseEntity.created(URI.create("/api/v1/products/" + productId + "/features/" + feature.id()))
                .body(feature);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.products.read')")
    List<FeatureView> list(@PathVariable UUID productId, @RequestParam(required = false) FeatureStatus status) {
        return catalog.listFeatures(productId, status);
    }

    @PatchMapping("/{featureId}")
    @PreAuthorize("hasAuthority('platform.products.manage')")
    FeatureView update(@PathVariable UUID productId, @PathVariable UUID featureId,
            @Valid @RequestBody CatalogRequests.UpdateFeature request) {
        return catalog.updateFeature(productId, featureId, request.name(), request.description());
    }

    @PostMapping("/{featureId}/deprecate")
    @PreAuthorize("hasAuthority('platform.products.manage')")
    @Operation(summary = "Deprecate a feature", description = "Existing plans keep it; new plans cannot add it.")
    FeatureView deprecate(@PathVariable UUID productId, @PathVariable UUID featureId) {
        return catalog.changeFeatureStatus(productId, featureId, FeatureStatus.DEPRECATED);
    }

    @PostMapping("/{featureId}/reactivate")
    @PreAuthorize("hasAuthority('platform.products.manage')")
    FeatureView reactivate(@PathVariable UUID productId, @PathVariable UUID featureId) {
        return catalog.changeFeatureStatus(productId, featureId, FeatureStatus.ACTIVE);
    }
}
