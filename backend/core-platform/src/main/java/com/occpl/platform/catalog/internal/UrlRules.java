package com.occpl.platform.catalog.internal;

import com.occpl.platform.shared.error.Problems.FieldViolation;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Product URLs must be absolute HTTPS URLs without credentials, query or fragment. Plain HTTP is
 * accepted only for local development hosts (localhost, 127.0.0.1, *.localhost).
 */
final class UrlRules {

    private UrlRules() {
    }

    static Optional<FieldViolation> check(String field, String value) {
        if (value == null) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception e) {
            return violation(field, "must be a valid absolute URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || host.isEmpty()) {
            return violation(field, "must be a valid absolute URL");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            return violation(field, "must not contain credentials, a query or a fragment");
        }
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.endsWith(".localhost");
        if (!scheme.equals("https") && !(scheme.equals("http") && local)) {
            return violation(field, "must use https (http is allowed only for localhost)");
        }
        return Optional.empty();
    }

    static String normalize(String value) {
        return value == null ? null : value.strip().replaceAll("/+$", "");
    }

    private static Optional<FieldViolation> violation(String field, String message) {
        return Optional.of(new FieldViolation(field, "Url", message));
    }

    static List<FieldViolation> checkAll(String appUrl, String apiBaseUrl) {
        return java.util.stream.Stream.of(check("appUrl", appUrl), check("apiBaseUrl", apiBaseUrl))
                .flatMap(Optional::stream).toList();
    }
}
