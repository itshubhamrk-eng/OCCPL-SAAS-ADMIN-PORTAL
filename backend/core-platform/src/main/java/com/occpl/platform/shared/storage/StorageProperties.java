package com.occpl.platform.shared.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param bucket   private S3 bucket; blank turns file storage off
 * @param region   AWS region of the bucket
 * @param endpoint optional S3-compatible endpoint (local testing); blank uses AWS
 */
@ConfigurationProperties("occpl.storage.s3")
public record StorageProperties(String bucket, String region, String endpoint) {

    boolean configured() {
        return bucket != null && !bucket.isBlank();
    }
}
