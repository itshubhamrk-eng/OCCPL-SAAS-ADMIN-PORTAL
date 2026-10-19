package com.occpl.platform.lead.internal;

import com.occpl.platform.notification.api.EmailMessage;
import org.springframework.web.util.HtmlUtils;

/** Renders the confirmation email sent to a newly registered lead. */
final class LeadEmails {

    private LeadEmails() {
    }

    static EmailMessage registered(Lead lead) {
        String subject = "Thank you for your interest in OCCPL";
        String interest = lead.productInterest();
        String text = """
                Hello %s,

                Thank you for your interest in OCCPL. We have registered %s and our team will get in touch with you shortly.

                Your details
                  Company:  %s
                  Contact:  %s
                  Email:    %s
                %s%s
                If anything above is wrong, just reply to this email and we will correct it.

                Regards,
                OCCPL Innovations
                """.formatted(lead.contactName(), lead.companyName(), lead.companyName(), lead.contactName(),
                lead.email(), lead.phone() == null ? "" : "  Phone:    " + lead.phone() + "\n",
                interest == null ? "" : "  Interested in: " + interest + "\n");
        StringBuilder rows = new StringBuilder();
        row(rows, "Company", lead.companyName());
        row(rows, "Contact", lead.contactName());
        row(rows, "Email", lead.email());
        row(rows, "Phone", lead.phone());
        row(rows, "Interested in", interest);
        String html = """
                <!DOCTYPE html>
                <html><body style="margin:0;padding:0;background:#f4f5f7;font-family:Arial,Helvetica,sans-serif;color:#1f2933;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f5f7;padding:24px 0;">
                <tr><td align="center">
                <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%%;background:#ffffff;border-radius:8px;overflow:hidden;">
                <tr><td style="background:#1d4ed8;padding:20px 28px;color:#ffffff;font-size:20px;font-weight:bold;">OCCPL Innovations</td></tr>
                <tr><td style="padding:28px;font-size:15px;line-height:1.6;">
                <p style="margin:0 0 16px;">Hello %s,</p>
                <p style="margin:0 0 16px;">Thank you for your interest in OCCPL. We have registered <strong>%s</strong> and our team will get in touch with you shortly.</p>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border:1px solid #e4e7eb;border-radius:6px;margin:0 0 16px;">
                %s</table>
                <p style="margin:0 0 16px;">If anything above is wrong, just reply to this email and we will correct it.</p>
                <p style="margin:0;">Regards,<br>OCCPL Innovations</p>
                </td></tr>
                </table>
                </td></tr></table>
                </body></html>
                """.formatted(HtmlUtils.htmlEscape(lead.contactName()), HtmlUtils.htmlEscape(lead.companyName()),
                rows);
        return new EmailMessage("lead-registered", lead.email(), subject, text, html, null);
    }

    private static void row(StringBuilder rows, String label, String value) {
        if (value == null) {
            return;
        }
        rows.append("<tr><td style=\"padding:8px 12px;color:#52606d;width:130px;border-bottom:1px solid #e4e7eb;\">")
                .append(label).append("</td><td style=\"padding:8px 12px;border-bottom:1px solid #e4e7eb;\">")
                .append(HtmlUtils.htmlEscape(value)).append("</td></tr>\n");
    }
}
