package com.occpl.platform.lead.internal;

import com.occpl.platform.notification.api.EmailMessage;
import org.springframework.web.util.HtmlUtils;

/** Renders the confirmation email sent to a newly registered lead. */
final class LeadEmails {

    private LeadEmails() {
    }

    static EmailMessage registered(Lead lead) {
        String subject = "Thank you for your interest in OCCPL SOFTECH";
        String interest = lead.productInterest();
        String text = """
                Hello %s,

                Thank you for your interest in OCCPL SOFTECH. We have registered %s and our team will get in touch with you shortly.

                Your details
                  Company:  %s
                  Contact:  %s
                  Email:    %s
                %s%s
                If anything above is wrong, just reply to this email and we will correct it.

                Regards,
                OCCPL SOFTECH

                --
                OCCPL SOFTECH Private Limited, Shop No. 6, Riverview Apartment, 264/4, Shaniwar Peth, Pune - 411030
                support@occplsoftech.com
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
                <tr><td style="padding:0;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0"><tr>
                <td align="center" style="background:#ffffff;padding:24px 24px 18px;border-bottom:4px solid #E11D48;">
                <img src="https://www.occplsoftech.com/logo-email.png" width="240" height="114" alt="OCCPL SOFTECH" style="display:block;margin:0 auto;border:0;outline:none;max-width:100%%;height:auto;"></td></tr></table>
                </td></tr>
                <tr><td style="padding:28px;font-size:15px;line-height:1.6;">
                <p style="margin:0 0 16px;">Hello %s,</p>
                <p style="margin:0 0 16px;">Thank you for your interest in OCCPL SOFTECH. We have registered <strong>%s</strong> and our team will get in touch with you shortly.</p>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border:1px solid #e4e7eb;border-radius:6px;margin:0 0 16px;">
                %s</table>
                <p style="margin:0 0 16px;">If anything above is wrong, just reply to this email and we will correct it.</p>
                <p style="margin:0;">Regards,<br>OCCPL SOFTECH</p>
                </td></tr>
                <tr><td align="center" style="background:#F3F4F6;padding:16px 24px;font-size:12px;line-height:1.5;color:#52606d;">
                OCCPL SOFTECH Private Limited, Shop No. 6, Riverview Apartment, 264/4, Shaniwar Peth, Pune - 411030<br>
                <a href="mailto:support@occplsoftech.com" style="color:#312E81;text-decoration:none;">support@occplsoftech.com</a>
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
