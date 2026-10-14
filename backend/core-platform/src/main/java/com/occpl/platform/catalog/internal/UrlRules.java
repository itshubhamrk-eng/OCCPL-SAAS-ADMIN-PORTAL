package com.occpl.platform.catalog.internal;

import com.occpl.platform.shared.error.Problems.FieldViolation;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Product URLs must be absolute HTTPS URLs without credentials, query or fragment. Core calls the
 * product's {@code apiBaseUrl} with its service token, so loopback, link-local (cloud metadata) and
 * unspecified addresses and local host names are refused. Only in development
 * ({@code occpl.catalog.allow-local-urls=true}) are local hosts and plain HTTP to them accepted
 * (localhost, 127.0.0.1, *.localhost).
 */
final class UrlRules {

    private static final Pattern LOCAL_IPV4 = Pattern.compile("^(127\\.\\d+\\.\\d+\\.\\d+|169\\.254\\.\\d+\\.\\d+|0\\.0\\.0\\.0)$");

    private UrlRules() {
    }

    static Optional<FieldViolation> check(String field, String value, boolean allowLocal) {
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
        boolean localName = host.equals("localhost") || host.endsWith(".localhost");
        boolean localAddress = LOCAL_IPV4.matcher(host).matches() || host.startsWith("[")
                && (host.equals("[::1]") || host.equals("[::]") || host.startsWith("[fe8") || host.startsWith("[fe9")
                        || host.startsWith("[fea") || host.startsWith("[feb") || host.startsWith("[::ffff:"));
        if ((localName || localAddress) && !allowLocal) {
            return violation(field, "must not point to a local or link-local address");
        }
        if (!scheme.equals("https") && !(scheme.equals("http") && allowLocal && (localName || host.equals("127.0.0.1")))) {
            return violation(field, "must use https");
        }
        return Optional.empty();
    }

    static String normalize(String value) {
        return value == null ? null : value.strip().replaceAll("/+$", "");
    }

    private static Optional<FieldViolation> violation(String field, String message) {
        return Optional.of(new FieldViolation(field, "Url", message));
    }

    static List<FieldViolation> checkAll(String appUrl, String apiBaseUrl, boolean allowLocal) {
        return Stream.of(check("appUrl", appUrl, allowLocal), check("apiBaseUrl", apiBaseUrl, allowLocal))
                .flatMap(Optional::stream).toList();
    }
}
