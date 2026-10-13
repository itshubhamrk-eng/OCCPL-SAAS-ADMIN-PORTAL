package com.occpl.platform.catalog.api;

import java.util.UUID;

public record ProductSummary(UUID id, String code, String name, ProductStatus status, String appUrl,
        String apiBaseUrl, String apiAudience, Integer currentVersionNo) {
}
