package com.occpl.platform.shared.error;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Stable, client-facing error codes (API_SPEC.md §2). Never rename an existing code. */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "Account disabled"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email address not verified"),
    IDENTITY_CONFLICT(HttpStatus.FORBIDDEN, "Identity cannot be linked"),
    COMPANY_SUSPENDED(HttpStatus.FORBIDDEN, "Company suspended"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    CONFLICT(HttpStatus.CONFLICT, "Conflict"),
    DUPLICATE_MEMBERSHIP(HttpStatus.CONFLICT, "Membership already exists"),
    INVITATION_PENDING(HttpStatus.CONFLICT, "Invitation already pending"),
    LAST_ADMIN(HttpStatus.CONFLICT, "Company must keep at least one administrator"),
    LIMIT_EXCEEDED(HttpStatus.CONFLICT, "Usage limit exceeded"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "Idempotency key reused with a different request"),
    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, "Resource was modified"),
    INVITATION_EXPIRED(HttpStatus.GONE, "Invitation expired"),
    INVALID_STATE_TRANSITION(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid state transition"),
    ENTITLEMENT_REQUIRED(HttpStatus.FORBIDDEN, "Product or feature not included in subscription"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"),
    PAYMENT_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY, "Payment gateway error"),
    BILLING_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "Billing is not configured"),
    STORAGE_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "File storage is not configured"),
    SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Service busy");

    private static final String TYPE_BASE = "https://api.occplsoftech.com/problems/";

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
