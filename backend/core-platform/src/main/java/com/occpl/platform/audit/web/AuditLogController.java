package com.occpl.platform.audit.web;

import com.occpl.platform.audit.internal.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuditLogController {

    private final AuditQueryService audit;

    AuditLogController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping("/api/v1/audit-logs")
    @PreAuthorize("hasAuthority('platform.audit.read')")
    @Operation(summary = "Search the platform audit log (newest first, keyset pagination)",
            description = "Pass nextCursor back as 'cursor'. 'action' matches a prefix, e.g. 'company.'.")
    AuditQueryService.EntryPage platform(@RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID actorUserId, @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType, @RequestParam(required = false) String targetId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int size) {
        return audit.search(new AuditQueryService.Filter(companyId, actorUserId, action, targetType, targetId, from, to),
                cursor, size);
    }

    @GetMapping("/api/v1/companies/{companyId}/audit-logs")
    @PreAuthorize("@tenant.check(#companyId, 'company.audit.read', 'platform.audit.read')")
    @Operation(summary = "Audit log of one company")
    AuditQueryService.EntryPage company(@PathVariable UUID companyId,
            @RequestParam(required = false) UUID actorUserId, @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int size) {
        return audit.search(new AuditQueryService.Filter(companyId, actorUserId, action, null, null, from, to),
                cursor, size);
    }
}
