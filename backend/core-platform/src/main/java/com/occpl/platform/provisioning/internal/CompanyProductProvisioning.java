package com.occpl.platform.provisioning.internal;

import com.occpl.platform.provisioning.api.ProvisioningState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Current provisioning state of one company in one product. */
@Entity
@Table(name = "company_product_provisioning")
@IdClass(CompanyProductProvisioning.Key.class)
class CompanyProductProvisioning {

    static class Key implements Serializable {
        UUID companyId;
        UUID productId;

        Key() {
        }

        Key(UUID companyId, UUID productId) {
            this.companyId = companyId;
            this.productId = productId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(companyId, k.companyId) && Objects.equals(productId, k.productId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(companyId, productId);
        }
    }

    @Id
    @Column(name = "company_id")
    private UUID companyId;

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProvisioningState state;

    @Column(name = "last_job_id")
    private UUID lastJobId;

    @Column(name = "provisioned_at")
    private Instant provisionedAt;

    @Column(name = "deprovision_at")
    private Instant deprovisionAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CompanyProductProvisioning() {
    }

    CompanyProductProvisioning(UUID companyId, UUID productId, Instant now) {
        this.companyId = companyId;
        this.productId = productId;
        this.state = ProvisioningState.PROVISIONING;
        this.updatedAt = now;
    }

    void change(ProvisioningState state, UUID jobId, Instant now) {
        this.state = state;
        if (jobId != null) {
            this.lastJobId = jobId;
        }
        if (state == ProvisioningState.PROVISIONED) {
            this.provisionedAt = now;
            this.deprovisionAt = null;
        } else if (state == ProvisioningState.DEPROVISIONED) {
            this.provisionedAt = null;
            this.deprovisionAt = null;
        }
        this.updatedAt = now;
    }

    void scheduleDeprovision(UUID jobId, Instant at, Instant now) {
        this.state = ProvisioningState.DEPROVISION_SCHEDULED;
        this.lastJobId = jobId;
        this.deprovisionAt = at;
        this.updatedAt = now;
    }

    /** Re-entitled before the scheduled removal: keep the existing tenant. */
    void keep(Instant now) {
        this.state = ProvisioningState.PROVISIONED;
        this.deprovisionAt = null;
        this.updatedAt = now;
    }

    UUID companyId() { return companyId; }
    UUID productId() { return productId; }
    ProvisioningState state() { return state; }
    UUID lastJobId() { return lastJobId; }
    Instant provisionedAt() { return provisionedAt; }
    Instant deprovisionAt() { return deprovisionAt; }
    Instant updatedAt() { return updatedAt; }
}
