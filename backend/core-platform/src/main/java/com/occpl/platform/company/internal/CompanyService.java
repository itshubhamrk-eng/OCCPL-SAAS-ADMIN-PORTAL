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
import com.occpl.platform.shared.storage.FileStorage;
import com.occpl.platform.shared.web.Etags;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final FileStorage files;

    CompanyService(CompanyRepository companies, CompanySettingsRepository settings, CompanyContactRepository contacts,
            CompanyStatusChangeRepository history, AuditLogger audit, ApplicationEventPublisher events, Clock clock,
            FileStorage files) {
        this.companies = companies;
        this.settings = settings;
        this.contacts = contacts;
        this.history = history;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.files = files;
    }

    public record CompanyView(UUID id, String code, String legalName, String displayName, CompanyStatus status,
            String country, String taxId, Profile profile, Instant createdAt, Instant updatedAt, long version) {
    }

    /** Optional profile; image links are signed and expire, so fetch them fresh rather than storing them. */
    public record Profile(String website, String addressLine1, String addressLine2, String city, String state,
            String postalCode, String gstNumber, String panNumber, String logoUrl, String faviconUrl) {
    }

    /** Profile values to store; a null or blank value clears the field. */
    public record ProfileData(String website, String addressLine1, String addressLine2, String city, String state,
            String postalCode, String gstNumber, String panNumber) {
    }

    /** Profile changes: null leaves a field unchanged, an empty string clears it. */
    public record ProfileChanges(String website, String addressLine1, String addressLine2, String city, String state,
            String postalCode, String gstNumber, String panNumber) {
    }

    public record NewCompany(String code, String legalName, String displayName, String country, String taxId,
            String timeZone, String locale, ContactData primaryContact, String initialAdminEmail,
            String initialAdminFirstName, String initialAdminLastName, ProfileData profile, ContactData supportContact) {
    }

    public record ContactData(String name, String email, String phone) {
    }

    public record CompanyChanges(String legalName, String displayName, String country, String taxId,
            ProfileChanges profile) {
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
        if (request.supportContact() != null) {
            saveContact(company.id(), CompanyContact.Type.SUPPORT, request.supportContact(), now);
        }
        if (request.profile() != null) {
            ProfileData p = request.profile();
            company.updateProfile(blankToNull(p.website()), blankToNull(p.addressLine1()), blankToNull(p.addressLine2()),
                    blankToNull(p.city()), blankToNull(p.state()), blankToNull(p.postalCode()),
                    blankToNull(p.gstNumber()), blankToNull(p.panNumber()), actor, now);
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
                .map(this::view);
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
        ProfileChanges profile = changes.profile();
        if (profile != null) {
            if (!platform && (profile.gstNumber() != null || profile.panNumber() != null)) {
                throw new ApiException(ErrorCode.FORBIDDEN,
                        "Only OCCPL staff can change the GST or PAN number. Contact support.");
            }
            String website = pickProfile("website", company.website(), profile.website(), diff);
            String line1 = pickProfile("addressLine1", company.addressLine1(), profile.addressLine1(), diff);
            String line2 = pickProfile("addressLine2", company.addressLine2(), profile.addressLine2(), diff);
            String city = pickProfile("city", company.city(), profile.city(), diff);
            String state = pickProfile("state", company.state(), profile.state(), diff);
            String postal = pickProfile("postalCode", company.postalCode(), profile.postalCode(), diff);
            String gst = pickProfile("gstNumber", company.gstNumber(), profile.gstNumber(), diff);
            String pan = pickProfile("panNumber", company.panNumber(), profile.panNumber(), diff);
            company.updateProfile(website, line1, line2, city, state, postal, gst, pan, user.userId(), clock.instant());
        }
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
        if (type == CompanyContact.Type.PRIMARY && (data.phone() == null || data.phone().isBlank())) {
            // Products such as the courier app create the company's first administrator from this
            // contact and need a phone number to do it.
            throw ApiException.validation("The primary contact needs a phone number in international format.");
        }
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
            throw ApiException.validation("Contact type must be PRIMARY, BILLING, TECHNICAL or SUPPORT.");
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

    private CompanyView view(Company c) {
        Profile profile = new Profile(c.website(), c.addressLine1(), c.addressLine2(), c.city(), c.state(),
                c.postalCode(), c.gstNumber(), c.panNumber(), signed(c.logoKey()), signed(c.faviconKey()));
        return new CompanyView(c.id(), c.code(), c.legalName(), c.displayName(), c.status(), c.country(), c.taxId(),
                profile, c.createdAt(), c.updatedAt(), c.version());
    }

    private String signed(String key) {
        return key == null ? null : files.signedUrl(key, IMAGE_LINK_VALIDITY).orElse(null);
    }

    private static String pickProfile(String field, String current, String requested, Map<String, Object> diff) {
        return pick(field, current, requested == null ? null : blankToNull(requested), requested != null, diff);
    }

    public static final Duration IMAGE_LINK_VALIDITY = Duration.ofHours(1);

    public enum Image {
        LOGO("logo", 512 * 1024), FAVICON("favicon", 128 * 1024);

        final String prefix;
        final int maxBytes;

        Image(String prefix, int maxBytes) {
            this.prefix = prefix;
            this.maxBytes = maxBytes;
        }
    }

    /**
     * Stores a company logo or favicon (PNG, JPEG, WebP, or ICO for favicons), replacing the previous one.
     * The file type is checked from the content, not the name or declared type.
     */
    @Transactional
    public CompanyView setImage(UUID companyId, Image image, byte[] content) {
        if (!files.enabled()) {
            throw new ApiException(ErrorCode.STORAGE_DISABLED,
                    "Image upload is not available: file storage is not configured.");
        }
        if (content == null || content.length == 0) {
            throw ApiException.validation("The file is empty.");
        }
        if (content.length > image.maxBytes) {
            throw ApiException.validation("The " + image.prefix + " must be at most " + (image.maxBytes / 1024) + " KB.");
        }
        ImageTypes.Detected type = ImageTypes.detect(content, image == Image.FAVICON)
                .orElseThrow(() -> ApiException.validation(image == Image.FAVICON
                        ? "The favicon must be a PNG, JPEG, WebP or ICO image."
                        : "The logo must be a PNG, JPEG or WebP image."));
        Company company = require(companyId);
        UUID actor = CurrentUser.require().userId();
        String key = "companies/" + companyId + "/" + image.prefix + "-" + UuidV7.generate(clock) + "." + type.extension();
        files.put(key, content, type.contentType());
        String previous = image == Image.LOGO ? company.logoKey() : company.faviconKey();
        if (image == Image.LOGO) {
            company.setLogoKey(key, actor, clock.instant());
        } else {
            company.setFaviconKey(key, actor, clock.instant());
        }
        companies.flush();
        if (previous != null) {
            files.delete(previous);
        }
        audit.record(AuditEvent.success("company." + image.prefix + "_changed", "company", companyId, companyId,
                Map.of("bytes", content.length)));
        return view(company);
    }

    @Transactional
    public CompanyView removeImage(UUID companyId, Image image) {
        Company company = require(companyId);
        String previous = image == Image.LOGO ? company.logoKey() : company.faviconKey();
        if (previous == null) {
            return view(company);
        }
        UUID actor = CurrentUser.require().userId();
        if (image == Image.LOGO) {
            company.setLogoKey(null, actor, clock.instant());
        } else {
            company.setFaviconKey(null, actor, clock.instant());
        }
        companies.flush();
        files.delete(previous);
        audit.record(AuditEvent.success("company." + image.prefix + "_removed", "company", companyId, companyId,
                Map.of()));
        return view(company);
    }

    private static SettingsView settingsView(CompanySettings s) {
        return new SettingsView(s.timeZone(), s.locale(), s.allowedEmailDomains(), s.version());
    }

    private static ContactView contactView(CompanyContact c) {
        return new ContactView(c.type().name(), c.name(), c.email(), c.phone());
    }
}
