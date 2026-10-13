package com.occpl.platform.shared.web;

import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;

/** Optimistic concurrency over HTTP: {@code ETag} carries the entity version, {@code If-Match} is checked. */
public final class Etags {

    private Etags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /** No-op when {@code ifMatch} is absent; otherwise it must equal the current version's ETag. */
    public static void check(String ifMatch, long currentVersion) {
        if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.strip().replace("W/", "").equals(of(currentVersion))) {
            throw new ApiException(ErrorCode.PRECONDITION_FAILED,
                    "The resource was modified by another request. Reload and retry.");
        }
    }
}
