package com.occpl.platform.catalog.api;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The product's current published configuration (validated against schema {@code schemaVersion}). */
public record PublishedConfig(UUID productId, int versionNo, int schemaVersion, JsonNode config) {
}
