package com.occpl.platform.catalog.api;

import java.util.UUID;

public record FeatureSummary(UUID id, UUID productId, String code, String name, FeatureType type, FeatureUnit unit,
        ResetPeriod resetPeriod, FeatureStatus status) {
}
