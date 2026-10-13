package com.occpl.platform.shared.web;

import java.util.Optional;
import org.slf4j.MDC;

/** Access to the current request's correlation id (stored in the logging MDC). */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }
}
