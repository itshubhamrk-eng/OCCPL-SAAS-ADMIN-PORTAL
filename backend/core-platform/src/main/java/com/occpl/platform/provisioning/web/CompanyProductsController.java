package com.occpl.platform.provisioning.web;

import com.occpl.platform.provisioning.internal.ProvisioningQueries;
import com.occpl.platform.provisioning.internal.ProvisioningQueries.CompanyProductView;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CompanyProductsController {

    private final ProvisioningQueries queries;

    CompanyProductsController(ProvisioningQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/api/v1/companies/{companyId}/products")
    @PreAuthorize("@tenant.check(#companyId, 'company.products.launch', 'platform.companies.read')")
    @Operation(summary = "Products of the company with launch links and provisioning state",
            description = "launchUrl is present only when the product can be opened now.")
    List<CompanyProductView> list(@PathVariable UUID companyId) {
        return queries.companyProducts(companyId);
    }
}
