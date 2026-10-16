package com.occpl.platform.company.internal;

import com.occpl.platform.company.api.BillingProfile;
import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.company.api.CompanyProfile;
import com.occpl.platform.company.api.CompanySummary;
import com.occpl.platform.shared.storage.FileStorage;
import java.time.Duration;
import com.occpl.platform.company.api.PrimaryContact;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class JpaCompanyDirectory implements CompanyDirectory {

    private final CompanyRepository repository;
    private final CompanySettingsRepository settings;
    private final CompanyContactRepository contacts;
    private final FileStorage files;

    JpaCompanyDirectory(CompanyRepository repository, CompanySettingsRepository settings,
            CompanyContactRepository contacts, FileStorage files) {
        this.files = files;
        this.repository = repository;
        this.settings = settings;
        this.contacts = contacts;
    }

    @Override
    public Optional<CompanySummary> find(UUID companyId) {
        return repository.findById(companyId).map(Company::toSummary);
    }

    @Override
    public Map<UUID, CompanySummary> findAll(Collection<UUID> companyIds) {
        if (companyIds.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(companyIds).stream()
                .map(Company::toSummary)
                .collect(Collectors.toMap(CompanySummary::id, Function.identity()));
    }

    @Override
    public List<String> allowedEmailDomains(UUID companyId) {
        return settings.findById(companyId).map(CompanySettings::allowedEmailDomains).orElse(List.of());
    }

    @Override
    public Optional<CompanyProfile> profile(UUID companyId) {
        return repository.findById(companyId).map(c -> new CompanyProfile(c.country(), c.website(), c.addressLine1(),
                c.addressLine2(), c.city(), c.state(), c.postalCode(), c.gstNumber(), c.panNumber(),
                sign(c.logoKey()), sign(c.faviconKey()),
                contacts.findByCompanyIdAndType(companyId, CompanyContact.Type.SUPPORT)
                        .map(k -> new PrimaryContact(k.name(), k.email(), k.phone())).orElse(null)));
    }

    private String sign(String key) {
        return key == null ? null : files.signedUrl(key, Duration.ofHours(24)).orElse(null);
    }

    @Override
    public Optional<PrimaryContact> primaryContact(UUID companyId) {
        return contacts.findByCompanyIdAndType(companyId, CompanyContact.Type.PRIMARY)
                .map(k -> new PrimaryContact(k.name(), k.email(), k.phone()));
    }

    @Override
    public Optional<BillingProfile> billingProfile(UUID companyId) {
        Optional<Company> company = repository.findById(companyId);
        Optional<CompanyContact> contact = contacts.findByCompanyIdAndType(companyId, CompanyContact.Type.BILLING)
                .or(() -> contacts.findByCompanyIdAndType(companyId, CompanyContact.Type.PRIMARY));
        if (company.isEmpty() || contact.isEmpty()) {
            return Optional.empty();
        }
        Company c = company.get();
        CompanyContact k = contact.get();
        return Optional.of(new BillingProfile(companyId, c.legalName(), c.country(), c.taxId(), k.name(), k.email(),
                k.phone()));
    }
}
