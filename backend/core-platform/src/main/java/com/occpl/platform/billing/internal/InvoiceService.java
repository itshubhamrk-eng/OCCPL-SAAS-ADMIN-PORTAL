package com.occpl.platform.billing.internal;

import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.billing.internal.PaymentGateway.GatewayException;
import com.occpl.platform.billing.internal.PaymentGateway.GatewayInvoice;
import com.occpl.platform.company.api.BillingProfile;
import com.occpl.platform.company.api.CompanyDirectory;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.id.UuidV7;
import com.occpl.platform.shared.security.Actor;
import com.occpl.platform.subscription.api.SubscriptionBilling;
import com.occpl.platform.subscription.api.SubscriptionBilling.NextPeriod;
import com.occpl.platform.subscription.api.SubscriptionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issues invoices through the gateway. The invoice row is committed (CREATING) before the gateway
 * call and updated after it, so a network call never holds a database transaction and an open
 * invoice per subscription period is enforced by the database (uk_invoice_open).
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

    private final InvoiceRepository invoices;
    private final PaymentGateway gateway;
    private final SubscriptionBilling subscriptions;
    private final CompanyDirectory companies;
    private final AuditLogger audit;
    private final BillingProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    InvoiceService(InvoiceRepository invoices, PaymentGateway gateway, SubscriptionBilling subscriptions,
            CompanyDirectory companies, AuditLogger audit, BillingProperties properties,
            TransactionTemplate transactions, Clock clock) {
        this.invoices = invoices;
        this.gateway = gateway;
        this.subscriptions = subscriptions;
        this.companies = companies;
        this.audit = audit;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Invoice for the subscription's next period, requested by platform staff. */
    public Invoice createForNextPeriod(UUID companyId, UUID subscriptionId) {
        requireEnabled();
        NextPeriod next = subscriptions.nextPeriod(subscriptionId).filter(n -> n.companyId().equals(companyId))
                .orElseThrow(() -> ApiException.notFound("Subscription"));
        if (next.status() == SubscriptionStatus.CANCELLED || next.status() == SubscriptionStatus.EXPIRED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "The subscription has ended.");
        }
        if (next.priceMinor() <= 0) {
            throw ApiException.validation("Free plans are not invoiced.");
        }
        return issue(next);
    }

    /** Renewal invoices due within the lead time (scheduled job). Returns how many were issued. */
    public int createDueInvoices() {
        if (!properties.enabled()) {
            return 0;
        }
        failStuckCreations();
        Instant until = clock.instant().plus(properties.invoiceLeadTime());
        int issued = 0;
        for (UUID subscriptionId : subscriptions.dueForInvoice(until, properties.batchSize() * 5)) {
            if (issued >= properties.batchSize()) {
                break;
            }
            NextPeriod next = subscriptions.nextPeriod(subscriptionId).orElse(null);
            if (next == null || invoices.existsOpenOrPaid(subscriptionId, next.periodStart())) {
                continue;
            }
            try {
                Invoice invoice = issue(next);
                if (invoice.status() == Invoice.Status.ISSUED) {
                    issued++;
                }
            } catch (ApiException e) {
                // e.g. no billing contact: visible in the audit log; staff fix the contact and it is retried next run
                log.warn("Renewal invoice for subscription {} not created: {}", subscriptionId, e.getMessage());
            }
        }
        return issued;
    }

    private Invoice issue(NextPeriod next) {
        BillingProfile profile = companies.billingProfile(next.companyId()).orElseThrow(() -> ApiException.validation(
                "The company needs a billing or primary contact with an email address before it can be invoiced."));
        Instant now = clock.instant();
        String description = next.planName() + " — " + DAY.format(next.periodStart()) + " to " + DAY.format(next.periodEnd());
        UUID actor = Actor.current().map(Actor::userId).orElse(null);
        Invoice invoice;
        try {
            invoice = transactions.execute(status -> invoices.saveAndFlush(new Invoice(UuidV7.generate(clock),
                    next.companyId(), next.subscriptionId(), gateway.name(), description, next.currency(),
                    next.priceMinor(), next.periodStart(), next.periodEnd(), profile.contactEmail(), actor, now)));
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(ErrorCode.CONFLICT, "An invoice for this period already exists.");
        }
        Instant expiresAt = now.plus(properties.paymentWindow());
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("occpl_invoice_id", invoice.id().toString());
        notes.put("company_id", next.companyId().toString());
        notes.put("subscription_id", next.subscriptionId().toString());
        if (profile.taxId() != null) {
            notes.put("customer_tax_id", profile.taxId());
        }
        try {
            GatewayInvoice created = gateway.createInvoice(new PaymentGateway.InvoiceRequest(invoice.id().toString(),
                    profile.legalName(), profile.contactEmail(), profile.contactPhone(), next.planName(),
                    DAY.format(next.periodStart()) + " – " + DAY.format(next.periodEnd()), next.currency(),
                    next.priceMinor(), expiresAt, properties.emailInvoices(), notes));
            return transactions.execute(status -> {
                Invoice saved = invoices.lockById(invoice.id()).orElseThrow();
                saved.issued(created, clock.instant());
                auditInvoice("billing.invoice_issued", saved, Map.of("gatewayInvoiceId", created.id(),
                        "amountMinor", saved.amountMinor(), "currency", saved.currency()));
                return saved;
            });
        } catch (GatewayException e) {
            log.warn("Gateway refused invoice {}: {}", invoice.id(), e.getMessage());
            transactions.executeWithoutResult(status -> {
                Invoice saved = invoices.lockById(invoice.id()).orElseThrow();
                saved.issueFailed(e.getMessage(), clock.instant());
                auditInvoice("billing.invoice_issue_failed", saved, Map.of("error", e.getMessage()));
            });
            throw new ApiException(ErrorCode.PAYMENT_GATEWAY_ERROR,
                    "The payment gateway could not create the invoice: " + e.getMessage());
        }
    }

    /** Cancels an open invoice at the gateway, then locally. */
    public Invoice cancel(UUID companyId, UUID invoiceId) {
        requireEnabled();
        Invoice invoice = invoices.findByIdAndCompanyId(invoiceId, companyId)
                .orElseThrow(() -> ApiException.notFound("Invoice"));
        if (!invoice.status().open()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Only unpaid issued invoices can be cancelled.");
        }
        GatewayInvoice cancelled = callGateway(() -> gateway.cancelInvoice(invoice.gatewayInvoiceId()));
        return transactions.execute(status -> {
            Invoice saved = invoices.lockById(invoiceId).orElseThrow();
            if ("paid".equals(cancelled.status())) {
                throw new ApiException(ErrorCode.CONFLICT, "The invoice was paid meanwhile; it cannot be cancelled.");
            }
            saved.close(Invoice.Status.CANCELLED, clock.instant());
            auditInvoice("billing.invoice_cancelled", saved, Map.of());
            return saved;
        });
    }

    /**
     * Reads the invoice's state from the gateway (fallback when a webhook was missed). A paid invoice
     * is processed exactly like the {@code invoice.paid} webhook.
     */
    public Invoice refresh(UUID companyId, UUID invoiceId, PaymentEvents events) {
        requireEnabled();
        Invoice invoice = invoices.findByIdAndCompanyId(invoiceId, companyId)
                .orElseThrow(() -> ApiException.notFound("Invoice"));
        if (invoice.gatewayInvoiceId() == null) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "The invoice was never issued at the gateway.");
        }
        GatewayInvoice remote = callGateway(() -> gateway.fetchInvoice(invoice.gatewayInvoiceId()));
        transactions.executeWithoutResult(status -> events.applyInvoiceState(invoice.gatewayInvoiceId(),
                remote.status(), remote.amountPaidMinor(), remote.paidAt(), null));
        return invoices.findById(invoiceId).orElseThrow();
    }

    /** An invoice left in CREATING (instance died during the gateway call) is marked for staff review. */
    private void failStuckCreations() {
        Instant before = clock.instant().minusSeconds(600);
        transactions.executeWithoutResult(status -> {
            for (Invoice invoice : invoices.stuckCreating(before)) {
                invoice.issueFailed("Creation was interrupted; check the gateway dashboard for receipt "
                        + invoice.id() + " before creating a new invoice.", clock.instant());
                auditInvoice("billing.invoice_issue_failed", invoice, Map.of("error", "interrupted"));
            }
        });
    }

    private GatewayInvoice callGateway(java.util.function.Supplier<GatewayInvoice> call) {
        try {
            return call.get();
        } catch (GatewayException e) {
            throw new ApiException(ErrorCode.PAYMENT_GATEWAY_ERROR, "The payment gateway reported: " + e.getMessage());
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED,
                    "Billing is not configured (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET).");
        }
    }

    void auditInvoice(String action, Invoice invoice, Map<String, Object> details) {
        Map<String, Object> all = new LinkedHashMap<>(details);
        all.put("subscriptionId", invoice.subscriptionId().toString());
        AuditEvent event = new AuditEvent(action, "invoice", invoice.id().toString(), invoice.companyId(),
                action.endsWith("failed") ? AuditEvent.Outcome.FAILURE : AuditEvent.Outcome.SUCCESS, all);
        if (Actor.current().isPresent()) {
            audit.record(event);
        } else {
            audit.recordSystem(event);
        }
    }
}
