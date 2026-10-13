package com.occpl.sample;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param provisioningClientId the only client whose tokens may call the provisioning endpoints
 * @param core how to reach Core's service API with this product's own client (client credentials)
 */
@ConfigurationProperties("sample")
public record SampleProperties(String provisioningClientId, Core core) {

    public record Core(String baseUrl, String tokenUri, String clientId, String clientSecret) {
    }
}
