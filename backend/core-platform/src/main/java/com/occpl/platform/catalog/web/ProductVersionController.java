package com.occpl.platform.catalog.web;

import com.occpl.platform.catalog.internal.CatalogService;
import com.occpl.platform.catalog.internal.CatalogService.VersionView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products/{productId}/versions")
class ProductVersionController {

    private final CatalogService catalog;

    ProductVersionController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('platform.products.manage')")
    @Operation(summary = "Create a configuration version (DRAFT, immutable)",
            description = "Validated against GET /api/v1/products/config-schema; secrets are rejected.")
    ResponseEntity<VersionView> create(@PathVariable UUID productId,
            @Valid @RequestBody CatalogRequests.NewVersion request) {
        VersionView version = catalog.createVersion(productId, request.config(), request.notes());
        return ResponseEntity.created(URI.create("/api/v1/products/" + productId + "/versions/" + version.versionNo()))
                .body(version);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.products.read')")
    List<VersionView> list(@PathVariable UUID productId) {
        return catalog.listVersions(productId);
    }

    @GetMapping("/{versionNo}")
    @PreAuthorize("hasAuthority('platform.products.read')")
    VersionView get(@PathVariable UUID productId, @PathVariable int versionNo) {
        return catalog.getVersion(productId, versionNo);
    }

    @PostMapping("/{versionNo}/publish")
    @PreAuthorize("hasAuthority('platform.products.manage')")
    @Operation(summary = "Make a version current", description = "Publishing an older version rolls back to it.")
    VersionView publish(@PathVariable UUID productId, @PathVariable int versionNo) {
        return catalog.publish(productId, versionNo);
    }

    @DeleteMapping("/{versionNo}")
    @PreAuthorize("hasAuthority('platform.products.manage')")
    @Operation(summary = "Delete a draft version", description = "Published versions are kept for history.")
    ResponseEntity<Void> delete(@PathVariable UUID productId, @PathVariable int versionNo) {
        catalog.deleteDraft(productId, versionNo);
        return ResponseEntity.noContent().build();
    }
}
