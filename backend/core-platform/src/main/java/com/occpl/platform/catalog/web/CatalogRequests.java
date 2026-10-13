package com.occpl.platform.catalog.web;

import com.occpl.platform.catalog.api.FeatureType;
import com.occpl.platform.catalog.api.FeatureUnit;
import com.occpl.platform.catalog.api.ResetPeriod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

final class CatalogRequests {

    static final String PRODUCT_CODE = "[a-z][a-z0-9-]{1,49}";
    static final String AUDIENCE = "[a-z][a-z0-9-]{2,99}";
    static final String FEATURE_CODE = "[a-z][a-z0-9_.-]{1,63}";

    private CatalogRequests() {
    }

    record RegisterProduct(
            @NotBlank @Pattern(regexp = PRODUCT_CODE, message = "lower-case letters, digits and hyphens; 2-50 characters, starting with a letter") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 500) String appUrl,
            @Size(max = 500) String apiBaseUrl,
            @Pattern(regexp = AUDIENCE, message = "lower-case letters, digits and hyphens; 3-100 characters") String apiAudience,
            @Pattern(regexp = AUDIENCE, message = "lower-case letters, digits and hyphens; 3-100 characters") String serviceClientId) {
    }

    record UpdateProduct(@Size(min = 1, max = 200) String name, @Size(max = 2000) String description,
            @Size(min = 1, max = 500) String appUrl, @Size(max = 500) String apiBaseUrl,
            @Pattern(regexp = AUDIENCE) String apiAudience,
            @Pattern(regexp = "|" + AUDIENCE, message = "empty, or lower-case letters, digits and hyphens; 3-100 characters") String serviceClientId) {
    }

    record Reason(@Size(max = 500) String reason) {
    }

    record NewVersion(@NotNull JsonNode config, @Size(max = 1000) String notes) {
    }

    record NewFeature(
            @NotBlank @Pattern(regexp = FEATURE_CODE, message = "lower-case letters, digits, '_', '.', '-'; 2-64 characters") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotNull FeatureType type,
            FeatureUnit unit,
            ResetPeriod resetPeriod) {
    }

    record UpdateFeature(@Size(min = 1, max = 200) String name, @Size(max = 1000) String description) {
    }
}
