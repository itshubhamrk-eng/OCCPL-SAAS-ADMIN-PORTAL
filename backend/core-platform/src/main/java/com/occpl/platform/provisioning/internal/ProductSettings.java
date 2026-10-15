package com.occpl.platform.provisioning.internal;

import com.occpl.platform.catalog.api.PublishedConfig;
import java.time.Duration;
import tools.jackson.databind.JsonNode;

/** The parts of a product's published configuration (schema v1) that provisioning and launching use. */
record ProductSettings(Integer versionNo, Mode mode, String provisioningPath, Duration timeout, int maxAttempts,
        String launchPath, boolean openInNewTab) {

    enum Mode { NONE, SYNC, ASYNC }

    static ProductSettings of(PublishedConfig config, ProvisioningProperties defaults) {
        JsonNode root = config.config();
        JsonNode provisioning = root.path("provisioning");
        JsonNode launch = root.path("launch");
        Mode mode = Mode.valueOf(provisioning.path("mode").asString("NONE"));
        Duration timeout = provisioning.has("timeoutSeconds")
                ? Duration.ofSeconds(provisioning.path("timeoutSeconds").asInt()) : defaults.defaultTimeout();
        int maxAttempts = provisioning.has("maxAttempts") ? provisioning.path("maxAttempts").asInt()
                : defaults.defaultMaxAttempts();
        return new ProductSettings(config.versionNo(), mode, provisioning.path("path").asString(null), timeout,
                maxAttempts, launch.path("path").asString(null), launch.path("openInNewTab").asBoolean(false));
    }

    static String expand(String template, java.util.UUID companyId, String companyCode) {
        return template.replace("{companyId}", companyId.toString()).replace("{companyCode}", companyCode);
    }
}
