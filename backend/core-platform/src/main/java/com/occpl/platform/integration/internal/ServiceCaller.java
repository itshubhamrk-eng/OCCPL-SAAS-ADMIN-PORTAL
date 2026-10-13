package com.occpl.platform.integration.internal;

import com.occpl.platform.catalog.api.ProductCatalog;
import com.occpl.platform.catalog.api.ProductStatus;
import com.occpl.platform.catalog.api.ProductSummary;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.security.ServicePrincipal;
import org.springframework.stereotype.Component;

/** Resolves the product a service request comes from. Unknown or retired clients are refused (403). */
@Component
public class ServiceCaller {

    private final ProductCatalog catalog;

    ServiceCaller(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    public ProductSummary product() {
        ServicePrincipal service = ServicePrincipal.current()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "A product service token is required."));
        return catalog.findByServiceClientId(service.clientId())
                .filter(p -> p.status() != ProductStatus.RETIRED)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN,
                        "This client is not registered as the service client of an active product."));
    }
}
