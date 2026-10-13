package com.occpl.platform.company.web;

import com.occpl.platform.company.internal.CompanyService;
import com.occpl.platform.shared.web.Etags;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies/{companyId}")
class CompanySettingsController {

    private final CompanyService companies;

    CompanySettingsController(CompanyService companies) {
        this.companies = companies;
    }

    @GetMapping("/settings")
    @PreAuthorize("@tenant.check(#companyId, 'company.profile.read', 'platform.companies.read')")
    ResponseEntity<CompanyService.SettingsView> settings(@PathVariable UUID companyId) {
        CompanyService.SettingsView view = companies.settings(companyId);
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(view);
    }

    @PutMapping("/settings")
    @PreAuthorize("@tenant.check(#companyId, 'company.profile.manage', 'platform.companies.manage')")
    ResponseEntity<CompanyService.SettingsView> updateSettings(@PathVariable UUID companyId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CompanyRequests.Settings request) {
        CompanyService.SettingsView view = companies.updateSettings(companyId, request.timeZone(), request.locale(),
                request.allowedEmailDomains(), ifMatch);
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(view);
    }

    @GetMapping("/contacts")
    @PreAuthorize("@tenant.check(#companyId, 'company.profile.read', 'platform.companies.read')")
    List<CompanyService.ContactView> contacts(@PathVariable UUID companyId) {
        return companies.contacts(companyId);
    }

    @PutMapping("/contacts/{type}")
    @PreAuthorize("@tenant.check(#companyId, 'company.profile.manage', 'platform.companies.manage')")
    CompanyService.ContactView saveContact(@PathVariable UUID companyId, @PathVariable String type,
            @Valid @RequestBody CompanyRequests.Contact request) {
        return companies.saveContact(companyId, type,
                new CompanyService.ContactData(request.name().strip(), request.email(), request.phone()));
    }

    @DeleteMapping("/contacts/{type}")
    @PreAuthorize("@tenant.check(#companyId, 'company.profile.manage', 'platform.companies.manage')")
    ResponseEntity<Void> deleteContact(@PathVariable UUID companyId, @PathVariable String type) {
        companies.deleteContact(companyId, type);
        return ResponseEntity.noContent().build();
    }
}
