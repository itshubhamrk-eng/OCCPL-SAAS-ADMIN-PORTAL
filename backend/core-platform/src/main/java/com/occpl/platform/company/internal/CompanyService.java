package com.occpl.platform.company.internal;

import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.company.api.CompanyCreated;
import com.occpl.platform.company.api.CompanyStatus;
import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.id.UuidV7;
import com.occpl.platform.shared.paging.Likes;
import com.occpl.platform.shared.paging.PageRequests;
import com.occpl.platform.shared.web.Etags;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Company lifecycle, profile, settings and contacts. Authorization is enforced by the controllers. */
@Service
public class CompanyService {

    static final String PLATFORM_MANAGE = "platform.companies.manage";
    private static final Map<String, String> SORTS = Map.of("createdAt", "createdAt", "displayName", "displayName",
            "code", "code", "status", "status");

    private final CompanyRepository companies;
    private final CompanySettingsRepository settings;
    private final CompanyContactRepository contacts;
    private final CompanyStatusChangeRepository history;
    private final AuditLogger audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    CompanyService(CompanyRepository companies, CompanySettingsRepository settings, CompanyContactRepository contacts,
            CompanyStatusChangeRepository history, AuditLogger audit, ApplicationEventPublisher events, Clock clock) {
        this.companies = companies;
        this.settings = settings;
        this.contacts = contacts;
        this.history = history;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public record CompanyView(UUID id, String code, String legalName, String displayName, CompanyStatus status,
            String country, String taxId, Instant createdAt, Instant updatedAt, long version) {
    }

    public record NewCompany(String code, String legalName, String displayName, String country, String taxId,
            String timeZone, String locale, ContactData primaryContact, String initialAdminEmail,
            String initialAdminFirstName, String initialAdminLastName) {
    }

    public record ContactData(String name, String email, String phone) {
    }

    public record CompanyChanges(String legalName, String displayName, String country, String taxId) {
    }

    public record SettingsView(String timeZone, String locale, List<String> allowedEmailDomains, long version) {
    }

    public record ContactView(String type, String name, String email, String phone) {
    }

    public record StatusChangeView(CompanyStatus fromStatus, CompanyStatus toStatus, String reason, UUID changedBy,
            Instant changedAt) {
    }

    @Transactional
    public CompanyView create(NewCompany request) {
        if (companies.existsByCode(request.code())) {
            throw new ApiException(ErrorCode.CONFLICT, "Company code '" + request.code() + "' is already in use.");
        }
        UUID actor = CurrentUser.require().userId();
        Instant now = clock.instant();
        Company company = companies.save(new Company(UuidV7.generate(clock), request.code(), request.legalName(),
                request.displayName(), request.country(), request.taxId(), actor, now));
        settings.save(new CompanySettings(company.id(), request.timeZone(), request.locale(), actor, now));
        if (request.primaryContact() != null) {
            saveContact(company.id(), CompanyContact.Type.PRIMARY, request.primaryContact(), now);
        }
        history.save(new CompanyStatusChange(UuidV7.generate(clock), company.id(), null, CompanyStatus.ACTIVE,
                "Company created", actor, now));
        audit.record(AuditEvent.success("company.created", "company", company.id(), company.id(),
                Map.of("code", company.code(), "initialAdminEmail", request.initialAdminEmail())));
        events.publishEvent(new CompanyCreated(company.id(), request.initialAdminEmail(),
                request.initialAdminFirstName(), request.initialAdminLastName()));
        return view(company);
    }

    @Transactional(readOnly = true)
    public Page<CompanyView> search(CompanyStatus status, String country, String q, Integer page, Integer size,
            List<String> sort) {
        return companies.search(status, country, Likes.prefix(q),
                        PageRequests.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(CompanyService::view);
    }

    @Transactional(readOnly = true)
    public CompanyView get(UUID companyId) {
        return view(require(companyId));
    }

    /**
     * Platform staff with {@code platform.companies.manage} may change every field; company
     * administrators may change only the display name (legal identity changes need verification).
     */
    @Transactional
    public CompanyView update(UUID companyId, CompanyChanges changes, String ifMatch) {
        Company company = require(companyId);
        Etags.check(ifMatch, company.version());
        CurrentUser user = CurrentUser.require();
        boolean platform = user.hasPlatformPermission(PLATFORM_MANAGE);
        if (!platform && (changes.legalName() != null || changes.country() != null || changes.taxId() != null)) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Only OCCPL staff can change the legal name, country or tax id. Contact support.");
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        String legalName = pick("legalName", company.legalName(), changes.legalName(), diff);
        String displayName = pick("displayName", company.displayName(), changes.displayName(), diff);
        String country = pick("country", company.country(), changes.country(), diff);
        String taxId = pick("taxId", company.taxId(), changes.taxId() == null ? null : blankToNull(changes.taxId()),
                changes.taxId() != null, diff);
        if (!diff.isEmpty()) {
            company.update(legalName, displayName, country, taxId, user.userId(), clock.instant());
            companies.flush();
            audit.record(AuditEvent.success("company.updated", "company", companyId, companyId, diff));
        }
        return view(company);
    }

    @Transactional
    public CompanyView changeStatus(UUID companyId, Company.Action action, String reason) {
        Company company = require(companyId);
        UUID actor = CurrentUser.require().userId();
        Instant now = clock.instant();
        CompanyStatus previous = company.transition(action, actor, now);
        companies.flush();
        history.save(new CompanyStatusChange(UuidV7.generate(clock), companyId, previous, company.status(), reason,
                actor, now));
        Map<String, Object> details = new HashMap<>();
        details.put("from", previous.name());
        details.put("to", company.status().name());
        if (reason != null) {
            details.put("reason", reason);
        }
        audit.record(AuditEvent.success("company." + action.name().toLowerCase(), "company", companyId, companyId,
                details));
        return view(company);
    }

    @Transactional(readOnly = true)
    public List<StatusChangeView> statusHistory(UUID companyId) {
        require(companyId);
        return history.findByCompanyIdOrderByChangedAtDesc(companyId).stream()
                .map(h -> new StatusChangeView(h.fromStatus(), h.toStatus(), h.reason(), h.changedBy(), h.changedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SettingsView settings(UUID companyId) {
        return settingsView(requireSettings(companyId));
    }

    @Transactional
    public SettingsView updateSettings(UUID companyId, String timeZone, String locale, List<String> domains,
            String ifMatch) {
        CompanySettings current = requireSettings(companyId);
        Etags.check(ifMatch, current.version());
        List<String> normalized = domains.stream().map(d -> d.strip().toLowerCase()).distinct().sorted().toList();
        current.update(timeZone, locale, normalized, CurrentUser.require().userId(), clock.instant());
        settings.flush();
        audit.record(AuditEvent.success("company.settings_updated", "company", companyId, companyId,
                Map.of("timeZone", timeZone, "locale", locale, "allowedEmailDomains", normalized)));
        return settingsView(current);
    }

    @Transactional(readOnly = true)
    public List<ContactView> contacts(UUID companyId) {
        require(companyId);
        return contacts.findByCompanyIdOrderByType(companyId).stream().map(CompanyService::contactView).toList();
    }

    @Transactional
    public ContactView saveContact(UUID companyId, String type, ContactData data) {
        require(companyId);
        CompanyContact contact = saveContact(companyId, contactType(type), data, clock.instant());
        audit.record(AuditEvent.success("company.contact_saved", "company", companyId, companyId,
                Map.of("type", contact.type().name())));
        return contactView(contact);
    }

    @Transactional
    public void deleteContact(UUID companyId, String type) {
        require(companyId);
        CompanyContact.Type contactType = contactType(type);
        if (contactType == CompanyContact.Type.PRIMARY) {
            throw new ApiException(ErrorCode.CONFLICT, "The primary contact can be changed but not removed.");
        }
        CompanyContact contact = contacts.findByCompanyIdAndType(companyId, contactType)
                .orElseThrow(() -> ApiException.notFound("Contact"));
        contacts.delete(contact);
        audit.record(AuditEvent.success("company.contact_deleted", "company", companyId, companyId,
                Map.of("type", contactType.name())));
    }

    private CompanyContact saveContact(UUID companyId, CompanyContact.Type type, ContactData data, Instant now) {
        CompanyContact contact = contacts.findByCompanyIdAndType(companyId, type)
                .orElseGet(() -> new CompanyContact(UuidV7.generate(clock), companyId, type));
        contact.update(data.name(), data.email().strip().toLowerCase(), data.phone(), now);
        return contacts.save(contact);
    }

    private Company require(UUID companyId) {
        return companies.findById(companyId).orElseThrow(() -> ApiException.notFound("Company"));
    }

    private CompanySettings requireSettings(UUID companyId) {
        require(companyId);
        return settings.findById(companyId).orElseThrow(() -> ApiException.notFound("Company settings"));
    }

    private static CompanyContact.Type contactType(String type) {
        try {
            return CompanyContact.Type.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("Contact type must be PRIMARY, BILLING or TECHNICAL.");
        }
    }

    private static String pick(String field, String current, String requested, Map<String, Object> diff) {
        return pick(field, current, requested, requested != null, diff);
    }

    private static String pick(String field, String current, String requested, boolean provided,
            Map<String, Object> diff) {
        if (!provided || Objects.equals(current, requested)) {
            return current;
        }
        diff.put(field, requested == null ? "" : requested);
        return requested;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static CompanyView view(Company c) {
        return new CompanyView(c.id(), c.code(), c.legalName(), c.displayName(), c.status(), c.country(), c.taxId(),
                c.createdAt(), c.updatedAt(), c.version());
    }

    private static SettingsView settingsView(CompanySettings s) {
        return new SettingsView(s.timeZone(), s.locale(), s.allowedEmailDomains(), s.version());
    }

    private static ContactView contactView(CompanyContact c) {
        return new ContactView(c.type().name(), c.name(), c.email(), c.phone());
    }
}
