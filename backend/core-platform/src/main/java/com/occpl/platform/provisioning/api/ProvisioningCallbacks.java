package com.occpl.platform.provisioning.api;

import java.util.UUID;

/** Completion reports from product services for asynchronous provisioning (integration contract). */
public interface ProvisioningCallbacks {

    /**
     * Completes a job of {@code productId} that is waiting for the product. A job of another product
     * is reported as not found. Repeating a successful report is accepted and changes nothing.
     *
     * @param success false records a failed attempt, retried with back-off until max attempts
     */
    void complete(UUID productId, UUID jobId, boolean success, String message);
}
