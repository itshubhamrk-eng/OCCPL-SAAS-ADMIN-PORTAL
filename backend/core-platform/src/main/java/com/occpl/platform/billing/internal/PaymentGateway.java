package com.occpl.platform.billing.internal;

import java.time.Instant;
import java.util.Map;

/** Port to the payment gateway (D-3: Razorpay today). Implementations make network calls; never call them in a transaction. */
interface PaymentGateway {

    String name();

    GatewayInvoice createInvoice(InvoiceRequest request);

    GatewayInvoice fetchInvoice(String gatewayInvoiceId);

    GatewayInvoice cancelInvoice(String gatewayInvoiceId);

    /**
     * @param receipt Core's invoice id, unique per invoice; lets support match the gateway's records
     */
    record InvoiceRequest(String receipt, String customerName, String customerEmail, String customerPhone,
            String lineItemName, String lineItemDescription, String currency, long amountMinor, Instant expiresAt,
            boolean emailCustomer, Map<String, String> notes) {
    }

    /** {@code status} in the gateway's words (Razorpay: draft, issued, partially_paid, paid, cancelled, expired). */
    record GatewayInvoice(String id, String number, String status, String paymentUrl, long amountPaidMinor,
            Instant issuedAt, Instant expiresAt, Instant paidAt) {
    }

    /** {@code retryable}: network error, timeout or 5xx; the request may be repeated. */
    class GatewayException extends RuntimeException {
        private final Integer status;
        private final boolean retryable;

        GatewayException(String message, Integer status, boolean retryable) {
            super(message);
            this.status = status;
            this.retryable = retryable;
        }

        Integer status() {
            return status;
        }

        boolean retryable() {
            return retryable;
        }
    }
}
