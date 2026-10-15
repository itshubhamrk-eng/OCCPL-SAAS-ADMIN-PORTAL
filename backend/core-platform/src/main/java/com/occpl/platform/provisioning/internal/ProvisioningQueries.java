package com.occpl.platform.provisioning.internal;

import com.occpl.platform.catalog.api.ProductCatalog;
import com.occpl.platform.catalog.api.ProductStatus;
import com.occpl.platform.catalog.api.ProductSummary;
import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.company.api.CompanyStatus;
import com.occpl.platform.company.api.CompanySummary;
import com.occpl.platform.entitlement.api.EntitlementStatus;
import com.occpl.platform.entitlement.api.Entitlements;
import com.occpl.platform.provisioning.api.ProvisioningState;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.paging.PageRequests;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side: provisioning jobs for platform staff and the product launcher for company members. */
@Service
public class ProvisioningQueries {

    private static final Map<String, String> SORTS = Map.of("createdAt", "createdAt", "updatedAt", "updatedAt",
            "status", "status");

    private final ProvisioningJobRepository jobs;
    private final CompanyProductProvisioningRepository states;
    private final ProductCatalog catalog;
    private final CompanyDirectory companies;
    private final Entitlements entitlements;
    private final ProvisioningProperties properties;

    ProvisioningQueries(ProvisioningJobRepository jobs, CompanyProductProvisioningRepository states,
            ProductCatalog catalog, CompanyDirectory companies, Entitlements entitlements,
            ProvisioningProperties properties) {
        this.jobs = jobs;
        this.states = states;
        this.catalog = catalog;
        this.companies = companies;
        this.entitlements = entitlements;
        this.properties = properties;
    }

    public record JobView(UUID id, UUID companyId, String companyCode, UUID productId, String productCode,
            ProvisioningJob.Operation operation, ProvisioningJob.Status status, int attempts, int maxAttempts,
            Instant nextAttemptAt, Instant callbackDeadline, Integer configVersionNo, Integer lastHttpStatus,
            String lastError, Instant createdAt, Instant updatedAt, Instant completedAt) {
    }

    /**
     * @param launchUrl set only when a member can open the product now (company active, entitlement
     *        allows access, tenant provisioned)
     */
    public record CompanyProductView(UUID productId, String productCode, String productName,
            EntitlementStatus entitlementStatus, Instant validUntil, ProvisioningState provisioningState,
            String launchUrl, boolean openInNewTab) {
    }

    @Transactional(readOnly = true)
    public Page<JobView> search(ProvisioningJob.Status status, ProvisioningJob.Operation operation, UUID companyId,
            UUID productId, Integer page, Integer size, List<String> sort) {
        Page<ProvisioningJob> result = jobs.search(status, operation, companyId, productId,
                PageRequests.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, CompanySummary> companyById = companies.findAll(
                result.getContent().stream().map(ProvisioningJob::companyId).collect(Collectors.toSet()));
        return result.map(j -> view(j, companyById.get(j.companyId())));
    }

    @Transactional(readOnly = true)
    public JobView get(UUID jobId) {
        ProvisioningJob job = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Provisioning job"));
        return view(job, companies.find(job.companyId()).orElse(null));
    }

    JobView view(ProvisioningJob j, CompanySummary company) {
        return new JobView(j.id(), j.companyId(), company == null ? null : company.code(), j.productId(),
                catalog.find(j.productId()).map(ProductSummary::code).orElse(null), j.operation(), j.status(),
                j.attempts(), j.maxAttempts(), j.nextAttemptAt(), j.callbackDeadline(), j.configVersionNo(),
                j.lastHttpStatus(), j.lastError(), j.createdAt(), j.updatedAt(), j.completedAt());
    }

    @Transactional(readOnly = true)
    public List<CompanyProductView> companyProducts(UUID companyId) {
        CompanySummary company = companies.find(companyId).orElseThrow(() -> ApiException.notFound("Company"));
        Map<UUID, CompanyProductProvisioning> stateByProduct = states.findByCompanyId(companyId).stream()
                .collect(Collectors.toMap(CompanyProductProvisioning::productId, Function.identity()));
        return entitlements.products(companyId).stream()
                .map(e -> catalog.find(e.productId()).filter(p -> p.status() == ProductStatus.ACTIVE)
                        .map(p -> productView(company, p, e, stateByProduct.get(p.id()))).orElse(null))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(CompanyProductView::productName))
                .toList();
    }

    private CompanyProductView productView(CompanySummary company, ProductSummary product,
            Entitlements.ProductEntitlement entitlement, CompanyProductProvisioning state) {
        ProvisioningState provisioning = state == null ? null : state.state();
        Optional<ProductSettings> settings = catalog.currentConfig(product.id())
                .map(c -> ProductSettings.of(c, properties));
        boolean launchable = company.status() == CompanyStatus.ACTIVE && entitlement.status().allowsAccess()
                && provisioning == ProvisioningState.PROVISIONED;
        String launchUrl = null;
        if (launchable) {
            String path = settings.map(ProductSettings::launchPath).orElse(null);
            launchUrl = product.appUrl().replaceAll("/+$", "")
                    + (path == null ? "/" : ProductSettings.expand(path, company.id(), company.code()));
        }
        return new CompanyProductView(product.id(), product.code(), product.name(), entitlement.status(),
                entitlement.validUntil(), provisioning, launchUrl,
                settings.map(ProductSettings::openInNewTab).orElse(false));
    }
}
