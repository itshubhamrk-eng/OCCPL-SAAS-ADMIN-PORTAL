package com.occpl.platform.company.internal;

import java.util.Optional;

/** Recognises the few image formats accepted for logos and favicons from their first bytes (not the file name). */
final class ImageTypes {

    record Detected(String contentType, String extension) {
    }

    private ImageTypes() {
    }

    static Optional<Detected> detect(byte[] b, boolean allowIco) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return Optional.of(new Detected("image/png", "png"));
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Optional.of(new Detected("image/jpeg", "jpg"));
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E'
                && b[10] == 'B' && b[11] == 'P') {
            return Optional.of(new Detected("image/webp", "webp"));
        }
        if (allowIco && b.length >= 6 && b[0] == 0 && b[1] == 0 && b[2] == 1 && b[3] == 0) {
            return Optional.of(new Detected("image/x-icon", "ico"));
        }
        return Optional.empty();
    }
}
