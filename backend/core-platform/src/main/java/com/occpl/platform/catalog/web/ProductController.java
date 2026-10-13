package com.occpl.platform.catalog.web;

import com.occpl.platform.catalog.api.ProductStatus;
import com.occpl.platform.catalog.internal.CatalogService;
import com.occpl.platform.catalog.internal.CatalogService.ProductData;
import com.occpl.platform.catalog.internal.CatalogService.ProductView;
import com.occpl.platform.catalog.internal.Product;
import com.occpl.platform.shared.paging.PageResponse;
import com.occpl.platform.shared.paging.SortParams;
import com.occpl.platform.shared.web.Etags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
class ProductController {

    private final CatalogService catalog;

    ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('platform.products.manage')")
    @Operation(summary = "Register a product (starts as DRAFT)",
            description = "apiAudience defaults to 'product-<code>' and must match the product service's Keycloak client audience.")
    ResponseEntity<ProductView> register(@Valid @RequestBody CatalogRequests.RegisterProduct request) {
        ProductView product = catalog.register(request.code(), new ProductData(request.name().strip(),
                request.description(), request.appUrl(), request.apiBaseUrl(), request.apiAudience()));
        return ResponseEntity.created(URI.create("/api/v1/products/" + product.id()))
                .eTag(Etags.of(product.version())).body(product);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.products.read')")
    PageResponse<ProductView> search(@RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Repeatable, e.g. sort=name,asc") SortParams sort) {
        return PageResponse.of(catalog.search(status, q, page, size, sort.values()), v -> v);
    }

    @GetMapping(value = "/config-schema", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('platform.products.read')")
    @Operation(summary = "JSON Schema that product configuration versions are validated against")
    String configSchema() {
        return catalog.configSchema();
    }

    @GetMapping("/{productId}")
    @PreAuthorize("hasAuthority('platform.products.read')")
    ResponseEntity<ProductView> get(@PathVariable UUID productId) {
        ProductView product = catalog.get(productId);
        return ResponseEntity.ok().eTag(Etags.of(product.version())).body(product);
    }

    @PatchMapping("/{productId}")
    @PreAuthorize("hasAuthority('platform.products.manage')")
    @Operation(summary = "Update product details", description = "code is immutable. Supports If-Match.")
    ResponseEntity<ProductView> update(@PathVariable UUID productId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CatalogRequests.UpdateProduct request) {
        ProductView product = catalog.update(productId, new ProductData(request.name(), request.description(),
                request.appUrl(), request.apiBaseUrl(), request.apiAudience()), ifMatch);
        return ResponseEntity.ok().eTag(Etags.of(product.version())).body(product);
    }

    @PostMapping("/{productId}/activate")
    @PreAuthorize("hasAuthority('platform.products.manage')")
    @Operation(summary = "Activate (or reinstate) a product", description = "Requires a published configuration version.")
    ProductView activate(@PathVariable UUID productId, @Valid @RequestBody(required = false) CatalogRequests.Reason request) {
        return catalog.changeStatus(productId, Product.Action.ACTIVATE, request == null ? null : request.reason());
    }

    @PostMapping("/{productId}/retire")
    @PreAuthorize("hasAuthority('platform.products.manage')")
    @Operation(summary = "Retire a product", description = "No new plans can include it; existing subscriptions continue.")
    ProductView retire(@PathVariable UUID productId, @Valid @RequestBody(required = false) CatalogRequests.Reason request) {
        return catalog.changeStatus(productId, Product.Action.RETIRE, request == null ? null : request.reason());
    }
}
