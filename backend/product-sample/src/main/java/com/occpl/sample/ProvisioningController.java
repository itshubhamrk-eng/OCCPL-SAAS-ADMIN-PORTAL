package com.occpl.sample;

import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Provisioning endpoints called by Core (SYNC mode). Both are idempotent: Core may repeat a call
 * after a timeout, with the job id as {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/provisioning/v1/tenants/{companyId}")
class ProvisioningController {

    private final TenantRepository tenants;
    private final NoteRepository notes;

    ProvisioningController(TenantRepository tenants, NoteRepository notes) {
        this.tenants = tenants;
        this.notes = notes;
    }

    record ProvisionRequest(String jobId, String companyId, @Size(max = 50) String companyCode,
            @Size(max = 200) String companyName) {
    }

    @PutMapping
    @Transactional
    ResponseEntity<Map<String, Object>> provision(@PathVariable UUID companyId, @RequestBody ProvisionRequest request) {
        if (request.companyId() != null && !request.companyId().equals(companyId.toString())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "companyId does not match the path.");
        }
        if (request.companyCode() == null || request.companyCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "companyCode is required.");
        }
        boolean existed = tenants.upsert(companyId, request.companyCode(), request.companyName(), request.jobId());
        return ResponseEntity.ok(Map.of("companyId", companyId.toString(), "status", "PROVISIONED",
                "created", !existed));
    }

    /** Removes all of the company's data; 404 if it was never provisioned or already removed. */
    @DeleteMapping
    @Transactional
    ResponseEntity<Void> deprovision(@PathVariable UUID companyId) {
        if (tenants.find(companyId).filter(TenantRepository.Tenant::active).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        notes.deleteAllOfCompany(companyId);
        tenants.markRemoved(companyId);
        return ResponseEntity.noContent().build();
    }
}
