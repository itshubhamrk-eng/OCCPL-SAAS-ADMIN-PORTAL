package com.occpl.platform.lead.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.occpl.platform.notification.api.EmailMessage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LeadEmailsTest {

    private static EmailMessage render(String phone, String interest) {
        Lead lead = new Lead(UUID.randomUUID(), "Acme <Ltd>", "Asha Rao", "asha@acme.test", phone, interest,
                "website", null, null, Instant.now());
        return LeadEmails.registered(lead);
    }

    @Test
    void usesOccplSoftechBranding() {
        EmailMessage m = render("+91 99999 00000", "HRMS");

        assertThat(m.subject()).isEqualTo("Thank you for your interest in OCCPL SOFTECH");
        assertThat(m.textBody()).contains("Thank you for your interest in OCCPL SOFTECH. We have registered Acme <Ltd>")
                .contains("support@occplsoftech.com").doesNotContain("Innovations");
        assertThat(m.htmlBody()).contains("https://www.occplsoftech.com/logo-email.png")
                .contains("alt=\"OCCPL SOFTECH\"").contains("width=\"240\"").contains("height=\"114\"")
                .contains("Shop No. 6, Riverview Apartment, 264/4, Shaniwar Peth, Pune - 411030")
                .contains("support@occplsoftech.com").contains("Acme &lt;Ltd&gt;")
                .doesNotContain("Innovations").doesNotContain("#1d4ed8");
    }

    @Test
    void keepsDetailsTable() {
        String html = render("+91 99999 00000", "HRMS").htmlBody();
        assertThat(html).contains(">Company<", ">Contact<", ">Email<", ">Phone<", ">Interested in<");
        assertThat(render(null, null).htmlBody()).doesNotContain(">Phone<").doesNotContain(">Interested in<");
    }
}
