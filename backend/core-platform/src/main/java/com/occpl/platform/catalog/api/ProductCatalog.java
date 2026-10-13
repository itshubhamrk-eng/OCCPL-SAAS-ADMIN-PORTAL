package com.occpl.platform.catalog.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to the catalogue for other modules (plans, entitlements, provisioning). */
public interface ProductCatalog {

    Optional<ProductSummary> find(UUID productId);

    Optional<ProductSummary> findByCode(String code);

    /** All features of the product, including deprecated ones. */
    List<FeatureSummary> features(UUID productId);

    Optional<PublishedConfig> currentConfig(UUID productId);
}
