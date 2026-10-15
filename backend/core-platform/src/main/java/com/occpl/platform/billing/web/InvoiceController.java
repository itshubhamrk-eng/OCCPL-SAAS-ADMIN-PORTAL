package com.occpl.platform.billing.web;

import com.occpl.platform.billing.internal.Invoice;
import com.occpl.platform.billing.internal.InvoiceQueries;
import com.occpl.platform.billing.internal.InvoiceQueries.BillingStatus;
import com.occpl.platform.billing.internal.InvoiceQueries.InvoiceView;
import com.occpl.platform.billing.internal.InvoiceService;
import com.occpl.platform.billing.internal.PaymentEvents;
import com.occpl.platform.membership.api.TenantContext;
import com.occpl.platform.shared.paging.PageResponse;
import com.occpl.platform.shared.paging.SortParams;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InvoiceController {

    private static final String COMPANY = "/api/v1/companies/{companyId}";

    private final InvoiceService invoices;
    private final InvoiceQueries queries;
    private final PaymentEvents events;

    InvoiceController(InvoiceService invoices, InvoiceQueries queries, PaymentEvents events) {
        this.invoices = invoices;
        this.queries = queries;
        this.events = events;
    }

    @GetMapping("/api/v1/billing/status")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Whether billing (gateway keys) and payment webhooks are configured")
    BillingStatus status() {
        return queries.status();
    }

    @GetMapping(COMPANY + "/invoices")
    @PreAuthorize("@tenant.check(#companyId, 'company.billing.read', 'platform.subscriptions.read')")
    @Operation(summary = "Billing history of the company, newest first",
            description = "paymentUrl is present on open invoices for viewers who may pay (company.billing.manage or staff).")
    PageResponse<InvoiceView> list(@PathVariable UUID companyId, @RequestParam(required = false) Invoice.Status status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size, SortParams sort) {
        return PageResponse.of(queries.forCompany(companyId, status, page, size, sort.values(), canPay(), staff()), v -> v);
    }

    @GetMapping(COMPANY + "/invoices/{invoiceId}")
    @PreAuthorize("@tenant.check(#companyId, 'company.billing.read', 'platform.subscriptions.read')")
    InvoiceView get(@PathVariable UUID companyId, @PathVariable UUID invoiceId) {
        return queries.get(companyId, invoiceId, canPay(), staff());
    }

    @PostMapping(COMPANY + "/subscriptions/{subscriptionId}/invoices")
    @PreAuthorize("hasAuthority('platform.subscriptions.manage')")
    @Operation(summary = "Issue an invoice for the subscription's next period through the gateway",
            description = "Razorpay emails the customer a payment link. 409 if one already exists for that period, 502 if the gateway refuses.")
    ResponseEntity<InvoiceView> create(@PathVariable UUID companyId, @PathVariable UUID subscriptionId) {
        Invoice invoice = invoices.createForNextPeriod(companyId, subscriptionId);
        return ResponseEntity.created(URI.create("/api/v1/companies/" + companyId + "/invoices/" + invoice.id()))
                .body(queries.get(companyId, invoice.id(), true, true));
    }

    @PostMapping(COMPANY + "/invoices/{invoiceId}/cancel")
    @PreAuthorize("hasAuthority('platform.subscriptions.manage')")
    @Operation(summary = "Cancel an unpaid invoice at the gateway")
    InvoiceView cancel(@PathVariable UUID companyId, @PathVariable UUID invoiceId) {
        invoices.cancel(companyId, invoiceId);
        return queries.get(companyId, invoiceId, true, true);
    }

    @PostMapping(COMPANY + "/invoices/{invoiceId}/refresh")
    @PreAuthorize("hasAuthority('platform.subscriptions.manage')")
    @Operation(summary = "Re-read the invoice from the gateway (if a webhook was missed)",
            description = "A paid invoice is processed exactly like the invoice.paid webhook.")
    InvoiceView refresh(@PathVariable UUID companyId, @PathVariable UUID invoiceId) {
        invoices.refresh(companyId, invoiceId, events);
        return queries.get(companyId, invoiceId, true, true);
    }

    @GetMapping("/api/v1/platform/invoices")
    @PreAuthorize("hasAuthority('platform.subscriptions.read')")
    @Operation(summary = "All invoices, newest first")
    PageResponse<InvoiceView> search(@RequestParam(required = false) Invoice.Status status,
            @RequestParam(required = false) UUID companyId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, SortParams sort) {
        return PageResponse.of(queries.search(status, companyId, page, size, sort.values()), v -> v);
    }

    /** Platform staff (membershipId null) or a member with company.billing.manage. */
    private static boolean canPay() {
        return TenantContext.current()
                .map(t -> t.membershipId() == null || t.permissions().contains("company.billing.manage"))
                .orElse(false);
    }

    private static boolean staff() {
        return TenantContext.current().map(t -> t.membershipId() == null).orElse(false);
    }
}
