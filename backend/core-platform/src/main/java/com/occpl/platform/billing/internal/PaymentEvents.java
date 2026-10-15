package com.occpl.platform.billing.internal;

import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.id.UuidV7;
import com.occpl.platform.subscription.api.SubscriptionBilling;
import com.occpl.platform.subscription.api.SubscriptionBilling.PaymentResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Processes verified gateway webhooks. Each event id is recorded once (duplicates are acknowledged
 * and ignored); events may arrive in any order, so every state change checks the current state.
 * Handled: {@code invoice.paid} (marks the invoice paid and activates/renews the subscription),
 * {@code invoice.partially_paid}, {@code invoice.expired}, {@code payment.captured},
 * {@code payment.failed}. Other events are recorded as ignored.
 */
@Service
public class PaymentEvents {

    private static final Logger log = LoggerFactory.getLogger(PaymentEvents.class);

    public enum Outcome { PROCESSED, DUPLICATE, IGNORED, UNKNOWN_INVOICE, AMOUNT_MISMATCH }

    private final InvoiceRepository invoices;
    private final PaymentTransactionRepository payments;
    private final SubscriptionBilling subscriptions;
    private final InvoiceService invoiceService;
    private final AuditLogger audit;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final MeterRegistry meters;
    private final BillingProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    PaymentEvents(InvoiceRepository invoices, PaymentTransactionRepository payments, SubscriptionBilling subscriptions,
            InvoiceService invoiceService, AuditLogger audit, JdbcClient jdbc, JsonMapper json, MeterRegistry meters,
            BillingProperties properties, TransactionTemplate transactions, Clock clock) {
        this.invoices = invoices;
        this.payments = payments;
        this.subscriptions = subscriptions;
        this.invoiceService = invoiceService;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.meters = meters;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Verifies and processes one Razorpay delivery. A bad signature is audited (in its own
     * transaction, so the record survives the refusal) and answered with 401.
     */
    public Outcome receive(byte[] body, String signature, String eventId) {
        if (!properties.webhooksEnabled()) {
            throw new ApiException(ErrorCode.BILLING_DISABLED, "Webhooks are not configured (RAZORPAY_WEBHOOK_SECRET).");
        }
        if (!WebhookSignature.valid(body, signature, properties.razorpayWebhookSecret())) {
            meters.counter("occpl.billing.webhooks", "outcome", "rejected").increment();
            log.warn("Rejected payment webhook with an invalid signature (event id {})", eventId);
            transactions.executeWithoutResult(status -> audit.recordSystem(new AuditEvent("billing.webhook_rejected",
                    "payment_webhook", null, null, AuditEvent.Outcome.FAILURE,
                    Map.of("reason", "invalid signature", "bodySha256", WebhookSignature.sha256(body)))));
            throw new WebhookRejected();
        }
        if (eventId == null || eventId.isBlank() || eventId.length() > 100) {
            throw ApiException.validation("The x-razorpay-event-id header is missing.");
        }
        JsonNode root = json.readTree(body);
        Outcome outcome = transactions.execute(status -> process(root, eventId, WebhookSignature.sha256(body)));
        return outcome;
    }

    private Outcome process(JsonNode root, String eventId, String bodyHash) {
        String type = root.path("event").asString("");
        if (!record(eventId, type, bodyHash)) {
            meters.counter("occpl.billing.webhooks", "outcome", "duplicate").increment();
            return Outcome.DUPLICATE;
        }
        JsonNode invoice = root.path("payload").path("invoice").path("entity");
        JsonNode payment = root.path("payload").path("payment").path("entity");
        Outcome outcome = switch (type) {
            case "invoice.paid", "invoice.partially_paid", "invoice.expired" -> applyInvoiceState(
                    invoice.path("id").asString(""), type.substring("invoice.".length()),
                    invoice.path("amount_paid").asLong(0), epoch(invoice.path("paid_at")), payment);
            case "payment.captured", "payment.failed" -> recordPayment(payment, null) ? Outcome.PROCESSED
                    : Outcome.UNKNOWN_INVOICE;
            default -> Outcome.IGNORED;
        };
        jdbc.sql("update payment_webhook_event set outcome = :outcome where gateway = 'RAZORPAY' and event_id = :id")
                .param("outcome", outcome.name()).param("id", eventId).update();
        meters.counter("occpl.billing.webhooks", "outcome", outcome.name().toLowerCase(), "event", type).increment();
        return outcome;
    }

    /**
     * Applies an invoice state reported by the gateway ({@code paid}, {@code partially_paid},
     * {@code expired}, {@code cancelled}); also used by the manual refresh.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome applyInvoiceState(String gatewayInvoiceId, String state, long amountPaidMinor, Instant paidAt,
            JsonNode payment) {
        Optional<Invoice> found = gatewayInvoiceId.isBlank() ? Optional.empty()
                : invoices.lockByGatewayId("RAZORPAY", gatewayInvoiceId);
        if (found.isEmpty()) {
            log.warn("Payment event for unknown gateway invoice {}", gatewayInvoiceId);
            return Outcome.UNKNOWN_INVOICE;
        }
        Invoice invoice = found.get();
        Instant now = clock.instant();
        if (payment != null && !payment.isMissingNode()) {
            recordPayment(payment, invoice);
        }
        switch (state) {
            case "paid" -> {
                if (amountPaidMinor < invoice.amountMinor()) {
                    // Never extend a subscription for less than the invoiced amount, whatever the event says.
                    log.warn("Invoice {} reported paid with {} of {}; left unpaid for staff review", invoice.id(),
                            amountPaidMinor, invoice.amountMinor());
                    invoiceService.auditInvoice("billing.payment_mismatch", invoice,
                            Map.of("amountPaidMinor", amountPaidMinor, "amountMinor", invoice.amountMinor()));
                    return Outcome.AMOUNT_MISMATCH;
                }
                if (invoice.markPaid(amountPaidMinor, paidAt, now)) {
                    PaymentResult result = subscriptions.applyPayment(invoice.subscriptionId(), invoice.periodStart());
                    invoice.paymentApplied(result);
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("amountPaidMinor", invoice.amountPaidMinor());
                    details.put("subscription", result.name());
                    invoiceService.auditInvoice("billing.invoice_paid", invoice, details);
                    if (result == PaymentResult.NOT_APPLICABLE) {
                        // Paid after the subscription ended: staff decide (reinstate or refund at the gateway).
                        log.warn("Invoice {} was paid but its subscription {} has ended; needs staff review",
                                invoice.id(), invoice.subscriptionId());
                    }
                }
            }
            case "partially_paid" -> invoice.markPartiallyPaid(amountPaidMinor, now);
            case "expired" -> {
                invoice.close(Invoice.Status.EXPIRED, now);
                invoiceService.auditInvoice("billing.invoice_expired", invoice, Map.of());
            }
            case "cancelled" -> invoice.close(Invoice.Status.CANCELLED, now);
            default -> {
                return Outcome.IGNORED;
            }
        }
        return Outcome.PROCESSED;
    }

    /** Records a payment once per payment id and outcome; returns false if its invoice is unknown. */
    private boolean recordPayment(JsonNode payment, Invoice knownInvoice) {
        String paymentId = payment.path("id").asString("");
        String status = switch (payment.path("status").asString("")) {
            case "captured" -> "CAPTURED";
            case "failed" -> "FAILED";
            default -> null;
        };
        if (paymentId.isBlank() || status == null) {
            return knownInvoice != null;
        }
        Invoice invoice = knownInvoice;
        if (invoice == null) {
            String invoiceId = payment.path("invoice_id").asString("");
            invoice = invoiceId.isBlank() ? null : invoices.lockByGatewayId("RAZORPAY", invoiceId).orElse(null);
            if (invoice == null) {
                return false;
            }
        }
        if (!payments.existsByGatewayPaymentIdAndStatus(paymentId, status)) {
            payments.save(new PaymentTransaction(UuidV7.generate(clock), invoice.id(), invoice.companyId(), paymentId,
                    status, payment.path("amount").asLong(0), payment.path("currency").asString(invoice.currency()),
                    payment.path("method").asString(null), payment.path("error_description").asString(null),
                    clock.instant()));
            if ("FAILED".equals(status)) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("paymentId", paymentId);
                details.put("reason", payment.path("error_description").asString("unknown"));
                audit.recordSystem(new AuditEvent("billing.payment_failed", "invoice", invoice.id().toString(),
                        invoice.companyId(), AuditEvent.Outcome.FAILURE, details));
            }
        }
        return true;
    }

    /** False if the event id was seen before. */
    private boolean record(String eventId, String type, String bodyHash) {
        try {
            jdbc.sql("""
                    insert into payment_webhook_event (gateway, event_id, event_type, body_sha256, outcome, received_at)
                    values ('RAZORPAY', :id, :type, :hash, 'RECEIVED', :at)""")
                    .param("id", eventId).param("type", type.length() > 64 ? type.substring(0, 64) : type)
                    .param("hash", bodyHash).param("at", Timestamp.from(clock.instant())).update();
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private static Instant epoch(JsonNode node) {
        return node.isNumber() && node.asLong() > 0 ? Instant.ofEpochSecond(node.asLong()) : null;
    }

    /** Invalid signature: answered 401 and audited by the controller. */
    public static class WebhookRejected extends RuntimeException {
        WebhookRejected() {
            super("Invalid webhook signature");
        }
    }
}
