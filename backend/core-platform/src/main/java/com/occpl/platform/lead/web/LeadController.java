package com.occpl.platform.lead.web;

import com.occpl.platform.lead.api.LeadStatus;
import com.occpl.platform.lead.internal.LeadService;
import com.occpl.platform.lead.internal.LeadService.LeadData;
import com.occpl.platform.lead.internal.LeadService.LeadView;
import com.occpl.platform.shared.paging.PageResponse;
import com.occpl.platform.shared.paging.SortParams;
import com.occpl.platform.shared.web.Etags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/leads")
class LeadController {

    private final LeadService leads;

    LeadController(LeadService leads) {
        this.leads = leads;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('platform.leads.manage')")
    @Operation(summary = "Register a lead (starts as NEW)")
    ResponseEntity<LeadView> register(@Valid @RequestBody LeadRequests.RegisterLead request) {
        LeadView lead = leads.register(new LeadData(request.companyName(), request.contactName(), request.email(),
                request.phone(), request.productInterest(), request.source(), request.notes()));
        return ResponseEntity.created(URI.create("/api/v1/leads/" + lead.id()))
                .eTag(Etags.of(lead.version())).body(lead);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.leads.read')")
    @Operation(summary = "Search leads", description = "q matches the start of company name, contact name or email.")
    PageResponse<LeadView> search(@RequestParam(required = false) LeadStatus status,
            @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Repeatable, e.g. sort=companyName,asc") SortParams sort) {
        return PageResponse.of(leads.search(status, q, page, size, sort.values()), v -> v);
    }

    @GetMapping("/{leadId}")
    @PreAuthorize("hasAuthority('platform.leads.read')")
    ResponseEntity<LeadView> get(@PathVariable UUID leadId) {
        LeadView lead = leads.get(leadId);
        return ResponseEntity.ok().eTag(Etags.of(lead.version())).body(lead);
    }

    @PatchMapping("/{leadId}")
    @PreAuthorize("hasAuthority('platform.leads.manage')")
    @Operation(summary = "Update lead details", description = "Omitted fields are unchanged; send an empty string to clear an optional field. Status changes use /status. Supports If-Match.")
    ResponseEntity<LeadView> update(@PathVariable UUID leadId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody LeadRequests.UpdateLead request) {
        LeadView lead = leads.update(leadId, new LeadData(request.companyName(), request.contactName(),
                request.email(), request.phone(), request.productInterest(), request.source(), request.notes()),
                ifMatch);
        return ResponseEntity.ok().eTag(Etags.of(lead.version())).body(lead);
    }

    @PostMapping("/{leadId}/status")
    @PreAuthorize("hasAuthority('platform.leads.manage')")
    @Operation(summary = "Change a lead's status",
            description = "NEW→CONTACTED|QUALIFIED|LOST, CONTACTED→QUALIFIED|LOST, QUALIFIED→CONVERTED|LOST, LOST→NEW. CONVERTED is final.")
    LeadView changeStatus(@PathVariable UUID leadId, @Valid @RequestBody LeadRequests.ChangeStatus request) {
        return leads.changeStatus(leadId, request.status(), request.reason());
    }
}
