package com.occpl.platform.shared.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Browser origins allowed to call the API. Empty means no cross-origin access. */
@ConfigurationProperties("occpl.security.cors")
record CorsProperties(List<String> allowedOrigins) {

    CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
