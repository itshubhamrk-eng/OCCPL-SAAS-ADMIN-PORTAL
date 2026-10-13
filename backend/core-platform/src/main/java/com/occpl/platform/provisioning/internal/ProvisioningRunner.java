package com.occpl.platform.provisioning.internal;

import com.occpl.platform.catalog.api.ProductCatalog;
import com.occpl.platform.catalog.api.ProductSummary;
import com.occpl.platform.catalog.api.PublishedConfig;
import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.company.api.CompanySummary;
import com.occpl.platform.provisioning.internal.ProductServiceClient.Call;
import com.occpl.platform.provisioning.internal.ProductServiceClient.Result;
import com.occpl.platform.provisioning.internal.ProvisioningJob.Operation;
import com.occpl.platform.provisioning.internal.ProvisioningJob.Status;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes due provisioning jobs on any number of instances:
 * <ol>
 *   <li>claim a batch ({@code SKIP LOCKED}) and give each job a lease covering its HTTP timeout — commit;</li>
 *   <li>call the product service <em>outside</em> any transaction;</li>
 *   <li>record the outcome under a row lock, only if the job is still in the claimed attempt (a callback
 *       or a cancellation may have completed it meanwhile).</li>
 * </ol>
 * A crash between 1 and 3 leaves the job RUNNING with an expired lease; it is then claimed again.
 * Product endpoints are idempotent (job id as {@code Idempotency-Key}), so repeating a call is safe.
 */
@Component
public class ProvisioningRunner {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningRunner.class);
    private static final Duration LEASE_MARGIN = Duration.ofSeconds(30);

    private final ProvisioningJobRepository jobs;
    private final ProvisioningService service;
    private final ProductServiceClient client;
    private final ProductCatalog catalog;
    private final CompanyDirectory companies;
    private final ProvisioningProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ProvisioningRunner(ProvisioningJobRepository jobs, ProvisioningService service, ProductServiceClient client,
            ProductCatalog catalog, CompanyDirectory companies, ProvisioningProperties properties,
            TransactionTemplate transactions, Clock clock) {
        this.jobs = jobs;
        this.service = service;
        this.client = client;
        this.catalog = catalog;
        this.companies = companies;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${occpl.provisioning.poll-interval:5s}",
            initialDelayString = "${occpl.provisioning.poll-interval:5s}")
    void scheduledRun() {
        try {
            runDue();
        } catch (RuntimeException e) {
            log.error("Provisioning run failed", e);
        }
    }

    private record Claimed(UUID jobId, int attempt, ProductSettings.Mode mode, Call call) {
    }

    /** Runs one batch of due jobs; returns how many jobs were processed. */
    public int runDue() {
        List<Claimed> claimed = transactions.execute(status -> claim(clock.instant()));
        if (claimed == null) {
            return 0;
        }
        for (Claimed c : claimed) {
            Result result = client.execute(c.call());
            transactions.executeWithoutResult(status -> record(c, result, clock.instant()));
        }
        return claimed.size();
    }

    private List<Claimed> claim(Instant now) {
        for (ProvisioningJob job : jobs.claimExpiredCallbacks(now, properties.batchSize())) {
            service.attemptFailed(job, "The product did not report completion before the callback deadline.", null,
                    true, now);
        }
        List<Claimed> claimed = new ArrayList<>();
        for (ProvisioningJob job : jobs.claimDue(now, properties.batchSize())) {
            prepare(job, now).ifPresent(claimed::add);
        }
        return claimed;
    }

    /** Resolves what to call; jobs that need no call (or cannot be called) are completed right here. */
    private Optional<Claimed> prepare(ProvisioningJob job, Instant now) {
        Optional<ProductSummary> product = catalog.find(job.productId());
        Optional<PublishedConfig> config = catalog.currentConfig(job.productId());
        Optional<CompanySummary> company = companies.find(job.companyId());
        if (product.isEmpty() || config.isEmpty() || company.isEmpty()) {
            service.failedPermanently(job, "The product has no published configuration.", now);
            return Optional.empty();
        }
        ProductSettings settings = ProductSettings.of(config.get(), properties);
        job.claim(settings.versionNo(), settings.maxAttempts(), settings.timeout().plus(LEASE_MARGIN), now);
        service.markRunning(job, now);
        if (settings.mode() == ProductSettings.Mode.NONE) {
            service.succeeded(job, null, now);
            return Optional.empty();
        }
        String baseUrl = product.get().apiBaseUrl();
        if (baseUrl == null || settings.provisioningPath() == null) {
            service.failedPermanently(job, "The product has no API base URL or provisioning path.", now);
            return Optional.empty();
        }
        URI uri = URI.create(baseUrl.replaceAll("/+$", "")
                + ProductSettings.expand(settings.provisioningPath(), job.companyId(), company.get().code()));
        return Optional.of(new Claimed(job.id(), job.attempts(), settings.mode(),
                new Call(job.id(), job.operation(), uri, body(job, product.get(), company.get(), settings),
                        settings.timeout())));
    }

    private Map<String, Object> body(ProvisioningJob job, ProductSummary product, CompanySummary company,
            ProductSettings settings) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.id().toString());
        body.put("companyId", company.id().toString());
        body.put("companyCode", company.code());
        body.put("companyName", company.displayName());
        body.put("productCode", product.code());
        body.put("configVersion", settings.versionNo());
        body.put("async", settings.mode() == ProductSettings.Mode.ASYNC);
        if (properties.callbackBaseUrl() != null && !properties.callbackBaseUrl().isBlank()) {
            body.put("callbackUrl", properties.callbackBaseUrl().replaceAll("/+$", "")
                    + "/api/v1/service/provisioning/jobs/" + job.id() + "/callback");
        }
        return body;
    }

    private void record(Claimed claimed, Result result, Instant now) {
        ProvisioningJob job = jobs.lockById(claimed.jobId()).orElse(null);
        if (job == null || job.status() != Status.RUNNING || job.attempts() != claimed.attempt()) {
            return; // completed by a callback, cancelled, or re-claimed after an expired lease
        }
        if (result.is2xx()) {
            if (claimed.mode() == ProductSettings.Mode.ASYNC && result.status() == 202) {
                job.awaitCallback(result.status(), now.plus(properties.callbackTimeout()), now);
            } else {
                service.succeeded(job, result.status(), now);
            }
            return;
        }
        Integer status = result.status();
        if (job.operation() == Operation.DEPROVISION && status != null && (status == 404 || status == 410)) {
            service.succeeded(job, status, now); // the tenant is already gone
            return;
        }
        log.warn("Provisioning job {} attempt {} failed: {}", job.id(), job.attempts(), result.error());
        service.attemptFailed(job, result.error(), status, result.retryable(), now);
    }
}
