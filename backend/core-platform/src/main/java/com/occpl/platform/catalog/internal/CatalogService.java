package com.occpl.platform.catalog.internal;

import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.catalog.api.FeatureStatus;
import com.occpl.platform.catalog.api.FeatureSummary;
import com.occpl.platform.catalog.api.FeatureType;
import com.occpl.platform.catalog.api.FeatureUnit;
import com.occpl.platform.catalog.api.ProductCatalog;
import com.occpl.platform.catalog.api.ProductStatus;
import com.occpl.platform.catalog.api.ProductSummary;
import com.occpl.platform.catalog.api.PublishedConfig;
import com.occpl.platform.catalog.api.ResetPeriod;
import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.error.Problems.FieldViolation;
import com.occpl.platform.shared.error.ValidationException;
import com.occpl.platform.shared.id.UuidV7;
import com.occpl.platform.shared.paging.Likes;
import com.occpl.platform.shared.paging.PageRequests;
import com.occpl.platform.shared.web.Etags;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/** Products, their configuration versions and features. Endpoint authorization is done by controllers. */
@Service
public class CatalogService implements ProductCatalog {

    private static final Map<String, String> SORTS = Map.of("name", "name", "code", "code", "createdAt", "createdAt",
            "status", "status");

    private final ProductRepository products;
    private final ProductVersionRepository versions;
    private final ProductFeatureRepository features;
    private final ProductConfigValidator validator;
    private final AuditLogger audit;
    private final JsonMapper jsonMapper;
    private final JsonMapper canonicalMapper;
    private final Clock clock;

    CatalogService(ProductRepository products, ProductVersionRepository versions, ProductFeatureRepository features,
            ProductConfigValidator validator, AuditLogger audit, JsonMapper jsonMapper, Clock clock) {
        this.products = products;
        this.versions = versions;
        this.features = features;
        this.validator = validator;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
        this.canonicalMapper = JsonMapper.builder().enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED).build();
        this.clock = clock;
    }

    public record ProductView(UUID id, String code, String name, String description, ProductStatus status,
            String appUrl, String apiBaseUrl, String apiAudience, String serviceClientId, Integer currentVersionNo,
            Instant createdAt,
            Instant updatedAt, long version) {
    }

    /** {@code serviceClientId}: the product service's Keycloak client for calls to Core (optional). */
    public record ProductData(String name, String description, String appUrl, String apiBaseUrl, String apiAudience,
            String serviceClientId) {
    }

    public record VersionView(int versionNo, int schemaVersion, String status, boolean current, String notes,
            Instant createdAt, UUID createdBy, Instant publishedAt, JsonNode config) {
    }

    public record FeatureView(UUID id, String code, String name, String description, FeatureType type,
            FeatureUnit unit, ResetPeriod resetPeriod, FeatureStatus status, Instant createdAt, long version) {
    }

    // ------------------------------------------------------------------ products

    @Transactional
    public ProductView register(String code, ProductData data) {
        if (products.existsByCode(code)) {
            throw new ApiException(ErrorCode.CONFLICT, "Product code '" + code + "' is already in use.");
        }
        String audience = data.apiAudience() == null ? "product-" + code : data.apiAudience();
        requireValidUrls(data.appUrl(), data.apiBaseUrl());
        if (products.existsByApiAudience(audience)) {
            throw new ApiException(ErrorCode.CONFLICT, "API audience '" + audience + "' is used by another product.");
        }
        String serviceClient = blankToNull(data.serviceClientId());
        requireFreeServiceClient(serviceClient, null);
        UUID actor = CurrentUser.require().userId();
        Product product = products.save(new Product(UuidV7.generate(clock), code, data.name(), data.description(),
                UrlRules.normalize(data.appUrl()), UrlRules.normalize(data.apiBaseUrl()), audience, serviceClient,
                actor, clock.instant()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code);
        details.put("apiAudience", audience);
        if (serviceClient != null) {
            details.put("serviceClientId", serviceClient);
        }
        audit.record(AuditEvent.success("product.registered", "product", product.id(), null, details));
        return view(product);
    }

    @Transactional(readOnly = true)
    public Page<ProductView> search(ProductStatus status, String q, Integer page, Integer size, List<String> sort) {
        return products.search(status, Likes.prefix(q),
                PageRequests.of(page, size, sort, SORTS, Sort.by(Sort.Direction.ASC, "name"))).map(this::view);
    }

    @Transactional(readOnly = true)
    public ProductView get(UUID productId) {
        return view(require(productId));
    }

    /** Omitted (null) fields stay unchanged; send an empty string to clear description or apiBaseUrl. */
    @Transactional
    public ProductView update(UUID productId, ProductData changes, String ifMatch) {
        Product product = require(productId);
        Etags.check(ifMatch, product.version());
        String name = changes.name() == null ? product.name() : changes.name();
        String description = changes.description() == null ? product.description() : blankToNull(changes.description());
        String appUrl = changes.appUrl() == null ? product.appUrl() : UrlRules.normalize(changes.appUrl());
        String apiBaseUrl = changes.apiBaseUrl() == null ? product.apiBaseUrl()
                : UrlRules.normalize(blankToNull(changes.apiBaseUrl()));
        String audience = changes.apiAudience() == null ? product.apiAudience() : changes.apiAudience();
        String serviceClient = changes.serviceClientId() == null ? product.serviceClientId()
                : blankToNull(changes.serviceClientId());
        requireValidUrls(appUrl, apiBaseUrl);
        if (!Objects.equals(serviceClient, product.serviceClientId())) {
            requireFreeServiceClient(serviceClient, productId);
        }
        if (!audience.equals(product.apiAudience()) && products.existsByApiAudienceAndIdNot(audience, productId)) {
            throw new ApiException(ErrorCode.CONFLICT, "API audience '" + audience + "' is used by another product.");
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diffField(diff, "name", product.name(), name);
        diffField(diff, "description", product.description(), description);
        diffField(diff, "appUrl", product.appUrl(), appUrl);
        diffField(diff, "apiBaseUrl", product.apiBaseUrl(), apiBaseUrl);
        diffField(diff, "apiAudience", product.apiAudience(), audience);
        diffField(diff, "serviceClientId", product.serviceClientId(), serviceClient);
        if (!diff.isEmpty()) {
            product.update(name, description, appUrl, apiBaseUrl, audience, serviceClient,
                    CurrentUser.require().userId(), clock.instant());
            products.flush();
            audit.record(AuditEvent.success("product.updated", "product", productId, null, diff));
        }
        return view(product);
    }

    @Transactional
    public ProductView changeStatus(UUID productId, Product.Action action, String reason) {
        Product product = require(productId);
        ProductStatus previous = product.transition(action, CurrentUser.require().userId(), clock.instant());
        products.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", previous.name());
        details.put("to", product.status().name());
        if (reason != null) {
            details.put("reason", reason);
        }
        audit.record(AuditEvent.success("product." + action.name().toLowerCase(), "product", productId, null, details));
        return view(product);
    }

    // ------------------------------------------------------------------ configuration versions

    public String configSchema() {
        return validator.schemaJson();
    }

    @Transactional
    public VersionView createVersion(UUID productId, JsonNode config, String notes) {
        require(productId);
        List<FieldViolation> violations = validator.validate(config);
        if (!violations.isEmpty()) {
            throw new ValidationException("The configuration is invalid.", violations);
        }
        String canonical = canonicalMapper.writeValueAsString(config);
        String hash = sha256(canonical);
        Optional<ProductVersion> latest = versions.findFirstByProductIdOrderByVersionNoDesc(productId);
        if (latest.isPresent() && latest.get().configHash().equals(hash)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "The configuration is identical to version " + latest.get().versionNo() + ".");
        }
        UUID actor = CurrentUser.require().userId();
        ProductVersion version = versions.saveAndFlush(new ProductVersion(UuidV7.generate(clock), productId,
                versions.maxVersionNo(productId) + 1, ProductConfigValidator.CURRENT_SCHEMA_VERSION, canonical, hash,
                blankToNull(notes), actor, clock.instant()));
        audit.record(AuditEvent.success("product.version_created", "product", productId, null,
                Map.of("versionNo", version.versionNo())));
        return versionView(version, null, true);
    }

    @Transactional(readOnly = true)
    public List<VersionView> listVersions(UUID productId) {
        Product product = require(productId);
        return versions.findByProductIdOrderByVersionNoDesc(productId).stream()
                .map(v -> versionView(v, product.currentVersionId(), false)).toList();
    }

    @Transactional(readOnly = true)
    public VersionView getVersion(UUID productId, int versionNo) {
        Product product = require(productId);
        return versionView(requireVersion(productId, versionNo), product.currentVersionId(), true);
    }

    /** Makes the version current. Publishing an older version is a rollback. */
    @Transactional
    public VersionView publish(UUID productId, int versionNo) {
        Product product = require(productId);
        ProductVersion version = requireVersion(productId, versionNo);
        UUID actor = CurrentUser.require().userId();
        Integer previous = currentVersionNo(product);
        version.publish(actor, clock.instant());
        product.makeCurrent(version.id(), actor, clock.instant());
        products.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("versionNo", versionNo);
        if (previous != null) {
            details.put("previousVersionNo", previous);
        }
        audit.record(AuditEvent.success("product.version_published", "product", productId, null, details));
        return versionView(version, product.currentVersionId(), true);
    }

    @Transactional
    public void deleteDraft(UUID productId, int versionNo) {
        require(productId);
        ProductVersion version = requireVersion(productId, versionNo);
        if (version.status() != ProductVersion.Status.DRAFT) {
            throw new ApiException(ErrorCode.CONFLICT, "Published versions are kept for history and cannot be deleted.");
        }
        versions.delete(version);
        audit.record(AuditEvent.success("product.version_deleted", "product", productId, null,
                Map.of("versionNo", versionNo)));
    }

    // ------------------------------------------------------------------ features

    @Transactional
    public FeatureView addFeature(UUID productId, String code, String name, String description, FeatureType type,
            FeatureUnit unit, ResetPeriod resetPeriod) {
        require(productId);
        List<FieldViolation> violations = new ArrayList<>();
        if (type == FeatureType.BOOLEAN && (unit != null || resetPeriod != null)) {
            violations.add(new FieldViolation("unit", "notAllowed", "BOOLEAN features have no unit or reset period"));
        }
        if (type == FeatureType.LIMIT) {
            if (unit == null) {
                violations.add(new FieldViolation("unit", "NotNull", "LIMIT features need a unit"));
            }
            if (resetPeriod == null) {
                violations.add(new FieldViolation("resetPeriod", "NotNull", "LIMIT features need a reset period"));
            }
            if (unit != null && unit.isStock() && resetPeriod != null && resetPeriod != ResetPeriod.NONE) {
                violations.add(new FieldViolation("resetPeriod", "stockUnit",
                        unit + " measures a current quantity and must use reset period NONE"));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("The feature definition is invalid.", violations);
        }
        if (features.existsByProductIdAndCode(productId, code)) {
            throw new ApiException(ErrorCode.CONFLICT, "Feature code '" + code + "' already exists for this product.");
        }
        ProductFeature feature = features.saveAndFlush(new ProductFeature(UuidV7.generate(clock), productId, code, name,
                blankToNull(description), type, unit, resetPeriod, clock.instant()));
        audit.record(AuditEvent.success("product.feature_added", "product", productId, null,
                Map.of("feature", code, "type", type.name())));
        return featureView(feature);
    }

    @Transactional(readOnly = true)
    public List<FeatureView> listFeatures(UUID productId, FeatureStatus status) {
        require(productId);
        return features.findByProduct(productId, status).stream().map(CatalogService::featureView).toList();
    }

    @Transactional
    public FeatureView updateFeature(UUID productId, UUID featureId, String name, String description) {
        ProductFeature feature = requireFeature(productId, featureId);
        feature.rename(name == null ? feature.name() : name,
                description == null ? feature.description() : blankToNull(description), clock.instant());
        features.flush();
        audit.record(AuditEvent.success("product.feature_updated", "product", productId, null,
                Map.of("feature", feature.code())));
        return featureView(feature);
    }

    @Transactional
    public FeatureView changeFeatureStatus(UUID productId, UUID featureId, FeatureStatus status) {
        ProductFeature feature = requireFeature(productId, featureId);
        if (feature.status() != status) {
            feature.changeStatus(status, clock.instant());
            features.flush();
            audit.record(AuditEvent.success("product.feature_" + (status == FeatureStatus.DEPRECATED ? "deprecated"
                    : "reactivated"), "product", productId, null, Map.of("feature", feature.code())));
        }
        return featureView(feature);
    }

    // ------------------------------------------------------------------ ProductCatalog (module API)

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductSummary> find(UUID productId) {
        return products.findById(productId).map(this::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductSummary> findByCode(String code) {
        return products.findByCode(code).map(this::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductSummary> findByServiceClientId(String clientId) {
        return clientId == null ? Optional.empty() : products.findByServiceClientId(clientId).map(this::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeatureSummary> features(UUID productId) {
        return features.findByProduct(productId, null).stream().map(ProductFeature::toSummary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublishedConfig> currentConfig(UUID productId) {
        return products.findById(productId).map(Product::currentVersionId).flatMap(versions::findById)
                .map(v -> new PublishedConfig(productId, v.versionNo(), v.schemaVersion(),
                        jsonMapper.readTree(v.configJson())));
    }

    // ------------------------------------------------------------------ helpers

    private Product require(UUID productId) {
        return products.findById(productId).orElseThrow(() -> ApiException.notFound("Product"));
    }

    private ProductVersion requireVersion(UUID productId, int versionNo) {
        return versions.findByProductIdAndVersionNo(productId, versionNo)
                .orElseThrow(() -> ApiException.notFound("Product version"));
    }

    private ProductFeature requireFeature(UUID productId, UUID featureId) {
        return features.findByIdAndProductId(featureId, productId).orElseThrow(() -> ApiException.notFound("Feature"));
    }

    private void requireFreeServiceClient(String serviceClient, UUID productId) {
        if (serviceClient != null && products.existsByServiceClientIdAndIdNot(serviceClient,
                productId == null ? new UUID(0, 0) : productId)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Service client '" + serviceClient + "' is used by another product.");
        }
    }

    private static void requireValidUrls(String appUrl, String apiBaseUrl) {
        List<FieldViolation> violations = UrlRules.checkAll(appUrl, apiBaseUrl);
        if (!violations.isEmpty()) {
            throw new ValidationException("One or more fields are invalid.", violations);
        }
    }

    private Integer currentVersionNo(Product product) {
        return product.currentVersionId() == null ? null
                : versions.findById(product.currentVersionId()).map(ProductVersion::versionNo).orElse(null);
    }

    private ProductView view(Product p) {
        return new ProductView(p.id(), p.code(), p.name(), p.description(), p.status(), p.appUrl(), p.apiBaseUrl(),
                p.apiAudience(), p.serviceClientId(), currentVersionNo(p), p.createdAt(), p.updatedAt(), p.version());
    }

    private ProductSummary summary(Product p) {
        return new ProductSummary(p.id(), p.code(), p.name(), p.status(), p.appUrl(), p.apiBaseUrl(), p.apiAudience(),
                p.serviceClientId(), currentVersionNo(p));
    }

    private VersionView versionView(ProductVersion v, UUID currentVersionId, boolean withConfig) {
        return new VersionView(v.versionNo(), v.schemaVersion(), v.status().name(), v.id().equals(currentVersionId),
                v.notes(), v.createdAt(), v.createdBy(), v.publishedAt(),
                withConfig ? jsonMapper.readTree(v.configJson()) : null);
    }

    private static FeatureView featureView(ProductFeature f) {
        FeatureSummary s = f.toSummary();
        return new FeatureView(f.id(), f.code(), f.name(), f.description(), s.type(), s.unit(), s.resetPeriod(),
                f.status(), f.createdAt(), f.version());
    }

    private static void diffField(Map<String, Object> diff, String field, String before, String after) {
        if (!Objects.equals(before, after)) {
            diff.put(field, after == null ? "" : after);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
