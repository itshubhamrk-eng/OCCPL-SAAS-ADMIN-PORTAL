package com.occpl.platform.billing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A payment outcome reported by the gateway (immutable). */
@Entity
@Table(name = "payment_transaction")
class PaymentTransaction {

    @Id
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "gateway_payment_id", nullable = false)
    private String gatewayPaymentId;

    @Column(nullable = false)
    private String status;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    private String method;

    @Column(name = "error_reason")
    private String errorReason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected PaymentTransaction() {
    }

    PaymentTransaction(UUID id, UUID invoiceId, UUID companyId, String gatewayPaymentId, String status,
            long amountMinor, String currency, String method, String errorReason, Instant occurredAt) {
        this.id = id;
        this.invoiceId = invoiceId;
        this.companyId = companyId;
        this.gatewayPaymentId = gatewayPaymentId;
        this.status = status;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.method = method;
        this.errorReason = errorReason == null || errorReason.length() <= 500 ? errorReason : errorReason.substring(0, 500);
        this.occurredAt = occurredAt;
    }

    String gatewayPaymentId() { return gatewayPaymentId; }
    String status() { return status; }
    long amountMinor() { return amountMinor; }
    String currency() { return currency; }
    String method() { return method; }
    String errorReason() { return errorReason; }
    Instant occurredAt() { return occurredAt; }
}
