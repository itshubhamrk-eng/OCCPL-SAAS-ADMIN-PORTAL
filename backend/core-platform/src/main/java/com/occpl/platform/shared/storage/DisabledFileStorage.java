package com.occpl.platform.shared.storage;

import java.time.Duration;
import java.util.Optional;

final class DisabledFileStorage implements FileStorage {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        throw new IllegalStateException("File storage is not configured (set FILES_S3_BUCKET).");
    }

    @Override
    public void delete(String key) {
        throw new IllegalStateException("File storage is not configured (set FILES_S3_BUCKET).");
    }

    @Override
    public Optional<String> signedUrl(String key, Duration validFor) {
        return Optional.empty();
    }
}
