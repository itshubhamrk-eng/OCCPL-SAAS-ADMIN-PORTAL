package com.occpl.platform.shared.storage;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Credentials come from the AWS default chain (instance role, or AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfiguration {

    @Bean
    FileStorage fileStorage(StorageProperties properties) {
        if (!properties.configured()) {
            return new DisabledFileStorage();
        }
        Region region = Region.of(properties.region() == null || properties.region().isBlank()
                ? "ap-south-1" : properties.region());
        boolean custom = properties.endpoint() != null && !properties.endpoint().isBlank();
        var client = S3Client.builder().region(region);
        var presigner = S3Presigner.builder().region(region);
        if (custom) {
            URI endpoint = URI.create(properties.endpoint());
            client.endpointOverride(endpoint).forcePathStyle(true);
            presigner.endpointOverride(endpoint)
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return new S3FileStorage(properties.bucket(), client.build(), presigner.build());
    }
}
