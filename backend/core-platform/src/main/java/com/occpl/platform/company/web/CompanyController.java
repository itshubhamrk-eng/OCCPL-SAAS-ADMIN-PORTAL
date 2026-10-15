package com.occpl.platform.company.web;

import com.occpl.platform.company.api.CompanyStatus;
import com.occpl.platform.company.internal.Company;
import com.occpl.platform.company.internal.CompanyService;
import com.occpl.platform.company.internal.CompanyService.CompanyView;
import com.occpl.platform.shared.paging.PageResponse;
import com.occpl.platform.shared.paging.SortParams;
import com.occpl.platform.shared.web.Etags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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
@RequestMapping("/api/v1/companies")
class CompanyController {

    private final CompanyService companies;

    CompanyController(CompanyService companies) {
        this.companies = companies;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('platform.companies.manage')")
    @Operation(summary = "Create a company and invite its first Company Admin")
    ResponseEntity<CompanyView> create(@Valid @RequestBody CompanyRequests.CreateCompany request) {
        CompanyService.ContactData contact = request.primaryContact() == null ? null
                : new CompanyService.ContactData(request.primaryContact().name(), request.primaryContact().email(),
                        request.primaryContact().phone());
        CompanyView created = companies.create(new CompanyService.NewCompany(request.code(), request.legalName().strip(),
                request.displayName().strip(), request.country(), blankToNull(request.taxId()),
                request.timeZone() == null ? "Asia/Kolkata" : request.timeZone(),
                request.locale() == null ? "en-IN" : request.locale(), contact,
                request.initialAdmin().email().strip().toLowerCase(), request.initialAdmin().firstName(),
                request.initialAdmin().lastName()));
        return ResponseEntity.created(URI.create("/api/v1/companies/" + created.id()))
                .eTag(Etags.of(created.version())).body(created);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.companies.read')")
    @Operation(summary = "Search companies", description = "q matches the start of the code or display name.")
    PageResponse<CompanyView> search(@RequestParam(required = false) CompanyStatus status,
            @RequestParam(required = false) String country, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @Parameter(description = "Repeatable, e.g. sort=createdAt,desc") SortParams sort) {
        return PageResponse.of(companies.search(status, country, q, page, size, sort.values()), v -> v);
    }

    @GetMapping("/{companyId}")
    @PreAuthorize("@tenant.check(#companyId, 'company.profile.read', 'platform.companies.read')")
    ResponseEntity<CompanyView> get(@PathVariable UUID companyId) {
        CompanyView company = companies.get(companyId);
        return ResponseEntity.ok().eTag(Etags.of(company.version())).body(company);
    }

    @PatchMapping("/{companyId}")
    @PreAuthorize("@tenant.check(#companyId, 'company.profile.manage', 'platform.companies.manage')")
    @Operation(summary = "Update company details",
            description = "Omitted fields are unchanged. Company admins may change only displayName. Supports If-Match.")
    ResponseEntity<CompanyView> update(@PathVariable UUID companyId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CompanyRequests.UpdateCompany request) {
        CompanyView company = companies.update(companyId, new CompanyService.CompanyChanges(request.legalName(),
                request.displayName(), request.country(), request.taxId()), ifMatch);
        return ResponseEntity.ok().eTag(Etags.of(company.version())).body(company);
    }

    @PostMapping("/{companyId}/activate")
    @PreAuthorize("hasAuthority('platform.companies.manage')")
    CompanyView activate(@PathVariable UUID companyId,
            @Valid @RequestBody(required = false) CompanyRequests.StatusChange request) {
        return companies.changeStatus(companyId, Company.Action.ACTIVATE, request == null ? null : request.reason());
    }

    @PostMapping("/{companyId}/suspend")
    @PreAuthorize("hasAuthority('platform.companies.manage')")
    @Operation(summary = "Suspend a company", description = "Members keep read-only access; products are blocked.")
    CompanyView suspend(@PathVariable UUID companyId, @Valid @RequestBody CompanyRequests.ReasonRequired request) {
        return companies.changeStatus(companyId, Company.Action.SUSPEND, request.reason());
    }

    @PostMapping("/{companyId}/deactivate")
    @PreAuthorize("hasAuthority('platform.companies.manage')")
    @Operation(summary = "Deactivate a company", description = "Members lose all access. Data is retained.")
    CompanyView deactivate(@PathVariable UUID companyId, @Valid @RequestBody CompanyRequests.ReasonRequired request) {
        return companies.changeStatus(companyId, Company.Action.DEACTIVATE, request.reason());
    }

    @GetMapping("/{companyId}/status-history")
    @PreAuthorize("@tenant.check(#companyId, 'company.profile.read', 'platform.companies.read')")
    List<CompanyService.StatusChangeView> statusHistory(@PathVariable UUID companyId) {
        return companies.statusHistory(companyId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
