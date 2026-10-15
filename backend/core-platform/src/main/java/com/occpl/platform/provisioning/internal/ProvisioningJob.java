package com.occpl.platform.provisioning.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** One provisioning or deprovisioning request for a company in a product service. */
@Entity
@Table(name = "provisioning_job")
public class ProvisioningJob {

    public enum Operation { PROVISION, DEPROVISION }

    public enum Status {
        PENDING, RUNNING, AWAITING_CALLBACK, SUCCEEDED, FAILED, CANCELLED;

        static final Set<Status> OPEN = EnumSet.of(PENDING, RUNNING, AWAITING_CALLBACK);
    }

    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Operation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "callback_deadline")
    private Instant callbackDeadline;

    @Column(name = "config_version_no")
    private Integer configVersionNo;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected ProvisioningJob() {
    }

    ProvisioningJob(UUID id, UUID companyId, UUID productId, Operation operation, int maxAttempts, Instant runAt,
            Instant now) {
        this.id = id;
        this.companyId = companyId;
        this.productId = productId;
        this.operation = operation;
        this.status = Status.PENDING;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = runAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Starts an attempt; the lease must outlast the HTTP call so no other runner claims the job meanwhile. */
    void claim(Integer configVersionNo, int maxAttempts, Duration lease, Instant now) {
        this.status = Status.RUNNING;
        this.attempts++;
        this.maxAttempts = Math.max(maxAttempts, attempts);
        this.configVersionNo = configVersionNo;
        this.lockedUntil = now.plus(lease);
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    void succeed(Integer httpStatus, Instant now) {
        this.status = Status.SUCCEEDED;
        this.lastHttpStatus = httpStatus;
        this.lastError = null;
        finish(now);
    }

    void awaitCallback(Integer httpStatus, Instant deadline, Instant now) {
        this.status = Status.AWAITING_CALLBACK;
        this.lastHttpStatus = httpStatus;
        this.callbackDeadline = deadline;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    /**
     * Records a failed attempt. Retryable failures are retried after {@code backoff} until
     * {@code maxAttempts}; returns true when the job is now permanently FAILED.
     */
    boolean failAttempt(String error, Integer httpStatus, boolean retryable, Duration backoff, Instant now) {
        this.lastError = truncate(error);
        this.lastHttpStatus = httpStatus;
        this.lockedUntil = null;
        this.callbackDeadline = null;
        if (retryable && attempts < maxAttempts) {
            this.status = Status.PENDING;
            this.nextAttemptAt = now.plus(backoff);
            this.updatedAt = now;
            return false;
        }
        this.status = Status.FAILED;
        finish(now);
        return true;
    }

    /** Fails without consuming an attempt (e.g. no configuration); never retried automatically. */
    void failPermanently(String error, Instant now) {
        this.lastError = truncate(error);
        this.status = Status.FAILED;
        finish(now);
    }

    void cancel(String reason, Instant now) {
        this.status = Status.CANCELLED;
        this.lastError = truncate(reason);
        finish(now);
    }

    /** Manual retry of a FAILED job: a fresh set of attempts, starting now. */
    void retry(Instant now) {
        this.status = Status.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.completedAt = null;
        this.updatedAt = now;
    }

    private void finish(Instant now) {
        this.lockedUntil = null;
        this.nextAttemptAt = null;
        this.callbackDeadline = null;
        this.completedAt = now;
        this.updatedAt = now;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    UUID id() { return id; }
    UUID companyId() { return companyId; }
    UUID productId() { return productId; }
    Operation operation() { return operation; }
    Status status() { return status; }
    int attempts() { return attempts; }
    int maxAttempts() { return maxAttempts; }
    Instant nextAttemptAt() { return nextAttemptAt; }
    Instant callbackDeadline() { return callbackDeadline; }
    Integer configVersionNo() { return configVersionNo; }
    Integer lastHttpStatus() { return lastHttpStatus; }
    String lastError() { return lastError; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
    Instant completedAt() { return completedAt; }
}
