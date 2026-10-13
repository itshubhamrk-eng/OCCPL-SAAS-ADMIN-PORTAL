package com.occpl.platform.catalog.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An immutable configuration snapshot. Only the publication fields ever change. */
@Entity
@Table(name = "product_version")
class ProductVersion {

    enum Status { DRAFT, PUBLISHED }

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, updatable = false, columnDefinition = "json")
    private String configJson;

    @Column(name = "config_hash", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String configHash;

    @Column(nullable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    @Column(updatable = false)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    protected ProductVersion() {
    }

    ProductVersion(UUID id, UUID productId, int versionNo, int schemaVersion, String configJson, String configHash,
            String notes, UUID createdBy, Instant now) {
        this.id = id;
        this.productId = productId;
        this.versionNo = versionNo;
        this.schemaVersion = schemaVersion;
        this.configJson = configJson;
        this.configHash = configHash;
        this.notes = notes;
        this.status = Status.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    /** First publication is recorded; re-publishing an older version (rollback) keeps its original stamp. */
    void publish(UUID by, Instant now) {
        if (status == Status.DRAFT) {
            status = Status.PUBLISHED;
            publishedAt = now;
            publishedBy = by;
        }
    }

    UUID id() { return id; }
    UUID productId() { return productId; }
    int versionNo() { return versionNo; }
    int schemaVersion() { return schemaVersion; }
    String configJson() { return configJson; }
    String configHash() { return configHash; }
    Status status() { return status; }
    String notes() { return notes; }
    Instant createdAt() { return createdAt; }
    UUID createdBy() { return createdBy; }
    Instant publishedAt() { return publishedAt; }
}
