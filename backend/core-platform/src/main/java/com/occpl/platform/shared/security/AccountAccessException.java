package com.occpl.platform.shared.security;

import com.occpl.platform.shared.error.ErrorCode;
import org.springframework.security.authentication.AccountStatusException;

/**
 * The token is valid but the account may not use the platform (disabled, unverified email, …).
 * Rendered as 403 with the given {@link ErrorCode} instead of a generic 401.
 */
public class AccountAccessException extends AccountStatusException {

    private final ErrorCode code;

    public AccountAccessException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
