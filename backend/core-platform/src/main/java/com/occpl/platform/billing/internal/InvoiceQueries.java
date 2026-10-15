package com.occpl.platform.billing.internal;

import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.paging.PageRequests;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoiceQueries {

    private static final Map<String, String> SORTS = Map.of("createdAt", "createdAt", "periodStart", "periodStart",
            "status", "status");

    private final InvoiceRepository invoices;
    private final PaymentTransactionRepository payments;
    private final BillingProperties properties;

    InvoiceQueries(InvoiceRepository invoices, PaymentTransactionRepository payments, BillingProperties properties) {
        this.invoices = invoices;
        this.payments = payments;
        this.properties = properties;
    }

    /**
     * @param paymentUrl only for open invoices and viewers allowed to pay
     * @param lastError only for platform staff
     */
    public record InvoiceView(UUID id, UUID companyId, UUID subscriptionId, Invoice.Status status, String description,
            String currency, long amountMinor, long amountPaidMinor, Instant periodStart, Instant periodEnd,
            String gatewayInvoiceId, String gatewayNumber, String paymentUrl, Instant issuedAt, Instant expiresAt,
            Instant paidAt, String paymentApplied, String lastError, Instant createdAt, List<PaymentView> payments) {
    }

    public record PaymentView(String gatewayPaymentId, String status, long amountMinor, String currency, String method,
            String errorReason, Instant occurredAt) {
    }

    public record BillingStatus(boolean enabled, boolean webhooksEnabled, String gateway) {
    }

    public BillingStatus status() {
        return new BillingStatus(properties.enabled(), properties.webhooksEnabled(), "RAZORPAY");
    }

    @Transactional(readOnly = true)
    public Page<InvoiceView> forCompany(UUID companyId, Invoice.Status status, Integer page, Integer size,
            List<String> sort, boolean canPay, boolean staff) {
        return invoices.findByCompany(companyId, status,
                PageRequests.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(i -> view(i, canPay, staff, false));
    }

    @Transactional(readOnly = true)
    public Page<InvoiceView> search(Invoice.Status status, UUID companyId, Integer page, Integer size, List<String> sort) {
        return invoices.search(status, companyId,
                PageRequests.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(i -> view(i, true, true, false));
    }

    @Transactional(readOnly = true)
    public InvoiceView get(UUID companyId, UUID invoiceId, boolean canPay, boolean staff) {
        return view(invoices.findByIdAndCompanyId(invoiceId, companyId)
                .orElseThrow(() -> ApiException.notFound("Invoice")), canPay, staff, true);
    }

    @Transactional(readOnly = true)
    public InvoiceView view(Invoice i, boolean canPay, boolean staff, boolean withPayments) {
        List<PaymentView> paymentViews = withPayments ? payments.findByInvoiceIdOrderByOccurredAt(i.id()).stream()
                .map(p -> new PaymentView(p.gatewayPaymentId(), p.status(), p.amountMinor(), p.currency(), p.method(),
                        p.errorReason(), p.occurredAt())).toList() : null;
        return new InvoiceView(i.id(), i.companyId(), i.subscriptionId(), i.status(), i.description(), i.currency(),
                i.amountMinor(), i.amountPaidMinor(), i.periodStart(), i.periodEnd(), i.gatewayInvoiceId(),
                i.gatewayNumber(), canPay && i.status().open() ? i.paymentUrl() : null, i.issuedAt(), i.expiresAt(),
                i.paidAt(), i.paymentApplied() == null ? null : i.paymentApplied().name(),
                staff ? i.lastError() : null, i.createdAt(), paymentViews);
    }
}
