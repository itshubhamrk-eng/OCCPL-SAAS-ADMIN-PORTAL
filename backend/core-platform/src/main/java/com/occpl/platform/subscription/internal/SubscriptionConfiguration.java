package com.occpl.platform.subscription.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SubscriptionProperties.class)
class SubscriptionConfiguration {
}
