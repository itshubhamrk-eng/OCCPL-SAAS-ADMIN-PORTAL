package com.occpl.platform.shared.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * Object storage for user-supplied files (company logos and favicons). Files are private; readers get
 * short-lived signed links. When no bucket is configured {@link #enabled()} is false and every other call
 * fails, so features that need storage can answer 503 with a clear message instead of failing obscurely.
 */
public interface FileStorage {

    boolean enabled();

    void put(String key, byte[] content, String contentType);

    void delete(String key);

    /** A signed link that works for {@code validFor}, or empty when storage is not configured. */
    Optional<String> signedUrl(String key, Duration validFor);
}
