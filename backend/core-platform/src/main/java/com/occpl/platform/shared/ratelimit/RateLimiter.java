package com.occpl.platform.shared.ratelimit;

/** Decides whether a request identified by a key may proceed. */
public interface RateLimiter {

    Decision tryConsume(String key, long limitPerMinute);

    /**
     * @param allowed false only when the limit is exhausted
     * @param remaining tokens left, or -1 when unknown (limiter unavailable)
     * @param retryAfterSeconds seconds until a token is available when not allowed
     */
    record Decision(boolean allowed, long remaining, long retryAfterSeconds) {

        static final Decision UNAVAILABLE = new Decision(true, -1, 0);
    }
}
