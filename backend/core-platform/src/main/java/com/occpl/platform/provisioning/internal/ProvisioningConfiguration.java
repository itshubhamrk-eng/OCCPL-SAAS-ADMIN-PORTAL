package com.occpl.platform.provisioning.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProvisioningProperties.class)
class ProvisioningConfiguration {
}
