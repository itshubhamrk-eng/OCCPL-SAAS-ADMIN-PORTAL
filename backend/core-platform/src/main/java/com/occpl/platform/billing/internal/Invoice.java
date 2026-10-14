package com.occpl.platform.billing.internal;

import com.occpl.platform.subscription.api.SubscriptionBilling.PaymentResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** An invoice issued through the gateway for one subscription period. */
@Entity
@Table(name = "invoice")
public class Invoice {

    public enum Status {
        /** Saved before the gateway call; becomes ISSUED or ISSUE_FAILED. */
        CREATING, ISSUE_FAILED, ISSUED, PARTIALLY_PAID, PAID, CANCELLED, EXPIRED;

        boolean open() {
            return this == ISSUED || this == PARTIALLY_PAID;
        }
    }

    private static final int MAX_ERROR = 1000;

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(nullable = false, updatable = false)
    private String gateway;

    @Column(name = "gateway_invoice_id")
    private String gatewayInvoiceId;

    @Column(name = "gateway_number")
    private String gatewayNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "amount_paid_minor", nullable = false)
    private long amountPaidMinor;

    @Column(name = "period_start", nullable = false, updatable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private Instant periodEnd;

    @Column(name = "payment_url")
    private String paymentUrl;

    @Column(name = "customer_email", nullable = false, updatable = false)
    private String customerEmail;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_applied")
    private PaymentResult paymentApplied;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Version
    private long version;

    protected Invoice() {
    }

    Invoice(UUID id, UUID companyId, UUID subscriptionId, String gateway, String description, String currency,
            long amountMinor, Instant periodStart, Instant periodEnd, String customerEmail, UUID createdBy, Instant now) {
        this.id = id;
        this.companyId = companyId;
        this.subscriptionId = subscriptionId;
        this.gateway = gateway;
        this.status = Status.CREATING;
        this.description = description;
        this.currency = currency;
        this.amountMinor = amountMinor;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.customerEmail = customerEmail;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void issued(PaymentGateway.GatewayInvoice gatewayInvoice, Instant now) {
        this.gatewayInvoiceId = gatewayInvoice.id();
        this.gatewayNumber = gatewayInvoice.number();
        this.paymentUrl = gatewayInvoice.paymentUrl();
        this.issuedAt = gatewayInvoice.issuedAt() != null ? gatewayInvoice.issuedAt() : now;
        this.expiresAt = gatewayInvoice.expiresAt();
        this.status = Status.ISSUED;
        this.lastError = null;
        this.updatedAt = now;
    }

    void issueFailed(String error, Instant now) {
        this.status = Status.ISSUE_FAILED;
        this.lastError = error == null || error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR);
        this.updatedAt = now;
    }

    /** Returns false if the invoice was already paid (repeated or late event). */
    boolean markPaid(long amountPaidMinor, Instant paidAt, Instant now) {
        if (status == Status.PAID) {
            return false;
        }
        this.status = Status.PAID;
        this.amountPaidMinor = Math.max(this.amountPaidMinor, amountPaidMinor);
        this.paidAt = paidAt != null ? paidAt : now;
        this.updatedAt = now;
        return true;
    }

    void markPartiallyPaid(long amountPaidMinor, Instant now) {
        if (status == Status.ISSUED || status == Status.PARTIALLY_PAID) {
            this.status = Status.PARTIALLY_PAID;
            this.amountPaidMinor = Math.max(this.amountPaidMinor, amountPaidMinor);
            this.updatedAt = now;
        }
    }

    /** Expired or cancelled at the gateway; a paid invoice keeps its state. */
    void close(Status closed, Instant now) {
        if (status != Status.PAID && status != closed) {
            this.status = closed;
            this.updatedAt = now;
        }
    }

    void paymentApplied(PaymentResult result) {
        this.paymentApplied = result;
    }

    public UUID id() { return id; }
    UUID companyId() { return companyId; }
    UUID subscriptionId() { return subscriptionId; }
    String gateway() { return gateway; }
    String gatewayInvoiceId() { return gatewayInvoiceId; }
    String gatewayNumber() { return gatewayNumber; }
    Status status() { return status; }
    String description() { return description; }
    String currency() { return currency; }
    long amountMinor() { return amountMinor; }
    long amountPaidMinor() { return amountPaidMinor; }
    Instant periodStart() { return periodStart; }
    Instant periodEnd() { return periodEnd; }
    String paymentUrl() { return paymentUrl; }
    String customerEmail() { return customerEmail; }
    Instant issuedAt() { return issuedAt; }
    Instant expiresAt() { return expiresAt; }
    Instant paidAt() { return paidAt; }
    PaymentResult paymentApplied() { return paymentApplied; }
    String lastError() { return lastError; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
}
