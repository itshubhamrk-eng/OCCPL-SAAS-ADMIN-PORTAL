/**
 * Billing through the payment gateway (decisions D-3/D-4: Razorpay creates, numbers and emails
 * invoices and collects payment; Core keeps references). Paid invoices activate or renew the
 * subscription through {@code SubscriptionBilling}. No other module depends on billing. Tax (GST)
 * treatment is open decision D-11: today the plan price is the invoice amount.
 */
package com.occpl.platform.billing;
