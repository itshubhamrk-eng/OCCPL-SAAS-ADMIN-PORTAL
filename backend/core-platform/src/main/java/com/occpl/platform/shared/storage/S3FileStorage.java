package com.occpl.platform.shared.storage;

import java.time.Duration;
import java.util.Optional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

final class S3FileStorage implements FileStorage {

    private final String bucket;
    private final S3Client client;
    private final S3Presigner presigner;

    S3FileStorage(String bucket, S3Client client, S3Presigner presigner) {
        this.bucket = bucket;
        this.client = client;
        this.presigner = presigner;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                .cacheControl("private, max-age=3600").build(), RequestBody.fromBytes(content));
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public Optional<String> signedUrl(String key, Duration validFor) {
        return Optional.of(presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(validFor)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build()).build()).url().toString());
    }
}
