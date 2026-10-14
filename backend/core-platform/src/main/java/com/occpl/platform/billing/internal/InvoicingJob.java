package com.occpl.platform.billing.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Creates renewal invoices ahead of period ends (hourly). Safe on several instances: the database
 * allows one open invoice per subscription period, so a concurrent duplicate is refused.
 */
@Component
class InvoicingJob {

    private static final Logger log = LoggerFactory.getLogger(InvoicingJob.class);
    private final InvoiceService invoices;

    InvoicingJob(InvoiceService invoices) {
        this.invoices = invoices;
    }

    @Scheduled(fixedDelayString = "${occpl.billing.invoicing-interval:1h}", initialDelayString = "PT2M")
    void run() {
        try {
            int issued = invoices.createDueInvoices();
            if (issued > 0) {
                log.info("Issued {} renewal invoice(s)", issued);
            }
        } catch (RuntimeException e) {
            log.error("Renewal invoicing failed", e);
        }
    }
}
