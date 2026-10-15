package com.occpl.platform.shared.error;

import java.sql.SQLTransientConnectionException;

/**
 * Recognises overload: no database connection became free within the pool's connection timeout
 * (HikariCP throws {@link SQLTransientConnectionException}, wrapped by Spring). Such requests are
 * answered 503 with {@code Retry-After} and logged in one line instead of a stack trace each, so an
 * overload does not turn into a log storm on top (load test H-3).
 */
public final class Overload {

    public static final String RETRY_AFTER_SECONDS = "2";

    private Overload() {
    }

    public static boolean isOverload(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    public static String detail() {
        return "The service is busy. Please retry shortly.";
    }
}
