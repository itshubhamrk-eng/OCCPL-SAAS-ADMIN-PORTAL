package com.occpl.platform.shared.error;

/**
 * Business or request error that maps to an RFC 9457 response. The detail message is returned to
 * clients, so it must never contain secrets or another tenant's data.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException notFound(String resource) {
        return new ApiException(ErrorCode.NOT_FOUND, resource + " not found.");
    }

    public static ApiException validation(String detail) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, detail);
    }
}
