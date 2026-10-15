package com.occpl.platform.provisioning.web;

import com.occpl.platform.provisioning.internal.ProvisioningJob;
import com.occpl.platform.provisioning.internal.ProvisioningQueries;
import com.occpl.platform.provisioning.internal.ProvisioningQueries.JobView;
import com.occpl.platform.provisioning.internal.ProvisioningService;
import com.occpl.platform.shared.paging.PageResponse;
import com.occpl.platform.shared.paging.SortParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/provisioning-jobs")
class ProvisioningJobController {

    private final ProvisioningQueries queries;
    private final ProvisioningService service;

    ProvisioningJobController(ProvisioningQueries queries, ProvisioningService service) {
        this.queries = queries;
        this.service = service;
    }

    record CancelRequest(@Size(max = 500) String reason) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.provisioning.read')")
    @Operation(summary = "Search provisioning jobs", description = "Newest first by default.")
    PageResponse<JobView> search(@RequestParam(required = false) ProvisioningJob.Status status,
            @RequestParam(required = false) ProvisioningJob.Operation operation,
            @RequestParam(required = false) UUID companyId, @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @Parameter(description = "Repeatable, e.g. sort=createdAt,desc") SortParams sort) {
        return PageResponse.of(queries.search(status, operation, companyId, productId, page, size, sort.values()),
                v -> v);
    }

    @GetMapping("/{jobId}")
    @PreAuthorize("hasAuthority('platform.provisioning.read')")
    JobView get(@PathVariable UUID jobId) {
        return queries.get(jobId);
    }

    @PostMapping("/{jobId}/retry")
    @PreAuthorize("hasAuthority('platform.provisioning.manage')")
    @Operation(summary = "Retry a FAILED job with a fresh set of attempts",
            description = "Only the latest job of a company and product can be retried.")
    JobView retry(@PathVariable UUID jobId) {
        service.retry(jobId);
        return queries.get(jobId);
    }

    @PostMapping("/{jobId}/cancel")
    @PreAuthorize("hasAuthority('platform.provisioning.manage')")
    @Operation(summary = "Cancel a PENDING or AWAITING_CALLBACK job")
    JobView cancel(@PathVariable UUID jobId, @Valid @RequestBody(required = false) CancelRequest request) {
        service.cancel(jobId, request == null ? null : request.reason());
        return queries.get(jobId);
    }
}
