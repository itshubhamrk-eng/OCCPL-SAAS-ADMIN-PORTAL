package com.occpl.platform.provisioning.internal;

import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.catalog.api.ProductCatalog;
import com.occpl.platform.catalog.api.ProductSummary;
import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.company.api.CompanySummary;
import com.occpl.platform.entitlement.api.EntitlementsChanged;
import com.occpl.platform.provisioning.api.ProvisioningCallbacks;
import com.occpl.platform.provisioning.api.ProvisioningState;
import com.occpl.platform.provisioning.internal.ProvisioningJob.Operation;
import com.occpl.platform.provisioning.internal.ProvisioningJob.Status;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.id.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps product tenants in line with entitlements:
 * <ul>
 *   <li>a newly entitled product gets a PROVISION job (or keeps its tenant if a removal was still pending);</li>
 *   <li>a product the company is no longer entitled to gets a DEPROVISION job scheduled after the data
 *       retention period (D-5), cancelled if the company is entitled again before then;</li>
 *   <li>job outcomes update the company's provisioning state; permanent failures raise alerts.</li>
 * </ul>
 */
@Service
public class ProvisioningService implements ProvisioningCallbacks {

    private final ProvisioningJobRepository jobs;
    private final CompanyProductProvisioningRepository states;
    private final ProductCatalog catalog;
    private final CompanyDirectory companies;
    private final ProvisioningAlerts alerts;
    private final AuditLogger audit;
    private final ProvisioningProperties properties;
    private final Clock clock;

    ProvisioningService(ProvisioningJobRepository jobs, CompanyProductProvisioningRepository states,
            ProductCatalog catalog, CompanyDirectory companies, ProvisioningAlerts alerts, AuditLogger audit,
            ProvisioningProperties properties, Clock clock) {
        this.jobs = jobs;
        this.states = states;
        this.catalog = catalog;
        this.companies = companies;
        this.alerts = alerts;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ entitlement changes

    /** Runs in the subscription change's transaction, so jobs exist exactly when the change commits. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onEntitlementsChanged(EntitlementsChanged event) {
        Instant now = clock.instant();
        for (UUID productId : event.addedProductIds()) {
            entitled(event.companyId(), productId, now);
        }
        for (UUID productId : event.removedProductIds()) {
            notEntitled(event.companyId(), productId, now);
        }
    }

    private void entitled(UUID companyId, UUID productId, Instant now) {
        CompanyProductProvisioning state = states.lock(companyId, productId).orElse(null);
        int cancelled = cancelOpen(companyId, productId, Operation.DEPROVISION, "Company is entitled again.", now);
        if (state != null && state.state() == ProvisioningState.DEPROVISION_SCHEDULED && cancelled > 0
                && state.provisionedAt() != null) {
            state.keep(now);
            audit.recordSystem(AuditEvent.success("provisioning.deprovision_cancelled", "company_product",
                    productId, companyId, Map.of("productId", productId.toString())));
            return;
        }
        if (state != null && (state.state() == ProvisioningState.PROVISIONED
                || state.state() == ProvisioningState.PROVISIONING)) {
            return;
        }
        ProvisioningJob job = newJob(companyId, productId, Operation.PROVISION, now, now);
        if (state == null) {
            state = new CompanyProductProvisioning(companyId, productId, now);
        }
        state.change(ProvisioningState.PROVISIONING, job.id(), now);
        states.save(state);
    }

    private void notEntitled(UUID companyId, UUID productId, Instant now) {
        CompanyProductProvisioning state = states.lock(companyId, productId).orElse(null);
        if (state == null) {
            return;
        }
        cancelOpen(companyId, productId, Operation.PROVISION, "Company is no longer entitled.", now);
        if (state.state() == ProvisioningState.DEPROVISIONED
                || state.state() == ProvisioningState.DEPROVISION_SCHEDULED) {
            return;
        }
        Instant at = now.plus(properties.deprovisionDelay());
        ProvisioningJob job = newJob(companyId, productId, Operation.DEPROVISION, at, now);
        state.scheduleDeprovision(job.id(), at, now);
        audit.recordSystem(AuditEvent.success("provisioning.deprovision_scheduled", "company_product", productId,
                companyId, Map.of("jobId", job.id().toString(), "deprovisionAt", at.toString())));
    }

    private ProvisioningJob newJob(UUID companyId, UUID productId, Operation operation, Instant runAt, Instant now) {
        return jobs.save(new ProvisioningJob(UuidV7.generate(clock), companyId, productId, operation,
                properties.defaultMaxAttempts(), runAt, now));
    }

    private int cancelOpen(UUID companyId, UUID productId, Operation operation, String reason, Instant now) {
        List<ProvisioningJob> open = jobs.findByCompanyIdAndProductIdAndOperationAndStatusIn(companyId, productId,
                operation, List.of(Status.PENDING, Status.AWAITING_CALLBACK));
        open.forEach(job -> job.cancel(reason, now));
        return open.size();
    }

    // ------------------------------------------------------------------ outcomes (runner and callbacks)

    /** Must run in a transaction holding the job's row lock. */
    void succeeded(ProvisioningJob job, Integer httpStatus, Instant now) {
        job.succeed(httpStatus, now);
        CompanyProductProvisioning state = stateOf(job, now);
        if (job.id().equals(state.lastJobId())) {
            state.change(job.operation() == Operation.PROVISION ? ProvisioningState.PROVISIONED
                    : ProvisioningState.DEPROVISIONED, null, now);
        }
        String productCode = productCode(job.productId());
        alerts.jobSucceeded(job, productCode);
        audit.recordSystem(AuditEvent.success("provisioning." + job.operation().name().toLowerCase() + "_succeeded",
                "provisioning_job", job.id(), job.companyId(), Map.of("product", productCode,
                        "attempts", job.attempts())));
    }

    /** Must run in a transaction holding the job's row lock. */
    void attemptFailed(ProvisioningJob job, String error, Integer httpStatus, boolean retryable, Instant now) {
        boolean permanent = job.failAttempt(error, httpStatus, retryable, properties.backoff(job.attempts()), now);
        if (permanent) {
            failed(job, now);
        }
    }

    void failedPermanently(ProvisioningJob job, String error, Instant now) {
        job.failPermanently(error, now);
        failed(job, now);
    }

    private void failed(ProvisioningJob job, Instant now) {
        CompanyProductProvisioning state = stateOf(job, now);
        if (job.id().equals(state.lastJobId())) {
            state.change(ProvisioningState.FAILED, null, now);
        }
        String productCode = productCode(job.productId());
        String companyCode = companies.find(job.companyId()).map(CompanySummary::code).orElse("?");
        alerts.jobFailed(job, productCode, companyCode);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("product", productCode);
        details.put("attempts", job.attempts());
        if (job.lastHttpStatus() != null) {
            details.put("httpStatus", job.lastHttpStatus());
        }
        audit.recordSystem(new AuditEvent("provisioning." + job.operation().name().toLowerCase() + "_failed",
                "provisioning_job", job.id().toString(), job.companyId(), AuditEvent.Outcome.FAILURE, details));
    }

    void markRunning(ProvisioningJob job, Instant now) {
        if (job.operation() == Operation.DEPROVISION) {
            CompanyProductProvisioning state = stateOf(job, now);
            if (job.id().equals(state.lastJobId())) {
                state.change(ProvisioningState.DEPROVISIONING, null, now);
            }
        }
    }

    private CompanyProductProvisioning stateOf(ProvisioningJob job, Instant now) {
        return states.lock(job.companyId(), job.productId()).orElseGet(() -> {
            CompanyProductProvisioning created = new CompanyProductProvisioning(job.companyId(), job.productId(), now);
            created.change(ProvisioningState.PROVISIONING, job.id(), now);
            return states.save(created);
        });
    }

    private String productCode(UUID productId) {
        return catalog.find(productId).map(ProductSummary::code).orElse("?");
    }

    // ------------------------------------------------------------------ callbacks from product services

    @Override
    @Transactional
    public void complete(UUID productId, UUID jobId, boolean success, String message) {
        ProvisioningJob job = jobs.lockById(jobId).filter(j -> j.productId().equals(productId))
                .orElseThrow(() -> ApiException.notFound("Provisioning job"));
        Instant now = clock.instant();
        if (job.status() == Status.SUCCEEDED && success) {
            return; // repeated report
        }
        if (job.status() != Status.AWAITING_CALLBACK && job.status() != Status.RUNNING) {
            throw new ApiException(ErrorCode.CONFLICT, "The job is " + job.status() + " and cannot be completed.");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("success", success);
        if (message != null) {
            details.put("message", message.length() > 300 ? message.substring(0, 300) : message);
        }
        audit.record(AuditEvent.success("provisioning.callback_received", "provisioning_job", jobId, job.companyId(),
                details));
        if (success) {
            succeeded(job, null, now);
        } else {
            attemptFailed(job, "Product reported failure: " + (message == null ? "(no message)" : message), null,
                    true, now);
        }
    }

    // ------------------------------------------------------------------ platform administration

    /** Manual retry of a FAILED job, if it is still the latest job of its company and product. */
    @Transactional
    public ProvisioningJob retry(UUID jobId) {
        ProvisioningJob job = jobs.lockById(jobId).orElseThrow(() -> ApiException.notFound("Provisioning job"));
        if (job.status() != Status.FAILED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Only FAILED jobs can be retried.");
        }
        Instant now = clock.instant();
        CompanyProductProvisioning state = stateOf(job, now);
        if (!job.id().equals(state.lastJobId())) {
            throw new ApiException(ErrorCode.CONFLICT, "A newer job exists for this company and product.");
        }
        job.retry(now);
        state.change(job.operation() == Operation.PROVISION ? ProvisioningState.PROVISIONING
                : ProvisioningState.DEPROVISIONING, null, now);
        audit.record(AuditEvent.success("provisioning.job_retried", "provisioning_job", jobId, job.companyId(),
                Map.of("operation", job.operation().name())));
        return job;
    }

    /** Cancels a PENDING or AWAITING_CALLBACK job (a running attempt finishes first). */
    @Transactional
    public ProvisioningJob cancel(UUID jobId, String reason) {
        ProvisioningJob job = jobs.lockById(jobId).orElseThrow(() -> ApiException.notFound("Provisioning job"));
        if (job.status() != Status.PENDING && job.status() != Status.AWAITING_CALLBACK) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only PENDING or AWAITING_CALLBACK jobs can be cancelled.");
        }
        Instant now = clock.instant();
        job.cancel(reason == null ? "Cancelled by platform staff." : reason, now);
        CompanyProductProvisioning state = stateOf(job, now);
        if (job.id().equals(state.lastJobId())) {
            boolean keepsTenant = job.operation() == Operation.DEPROVISION && state.provisionedAt() != null;
            state.change(keepsTenant ? ProvisioningState.PROVISIONED : ProvisioningState.FAILED, null, now);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("operation", job.operation().name());
        if (reason != null) {
            details.put("reason", reason);
        }
        audit.record(AuditEvent.success("provisioning.job_cancelled", "provisioning_job", jobId, job.companyId(),
                details));
        return job;
    }
}
