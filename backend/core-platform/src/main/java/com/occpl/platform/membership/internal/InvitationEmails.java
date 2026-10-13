package com.occpl.platform.membership.internal;

import com.occpl.platform.notification.api.EmailMessage;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.web.util.HtmlUtils;

/** Renders the invitation email for users who already have a sign-in account. */
final class InvitationEmails {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private InvitationEmails() {
    }

    static EmailMessage invitation(InvitationIssued event, String acceptLink) {
        String inviter = event.inviterName() == null ? "An administrator" : event.inviterName();
        String expires = DATE.format(event.expiresAt());
        String subject = "You're invited to join " + event.companyName() + " on OCCPL";
        String text = """
                Hello,

                %s has invited you to join %s on the OCCPL platform.

                Accept the invitation (sign in with your existing OCCPL account):
                %s

                This invitation expires on %s. If you were not expecting it, you can ignore this email.
                """.formatted(inviter, event.companyName(), acceptLink, expires);
        String html = """
                <p>Hello,</p>
                <p>%s has invited you to join <strong>%s</strong> on the OCCPL platform.</p>
                <p><a href="%s">Accept the invitation</a> (sign in with your existing OCCPL account).</p>
                <p>This invitation expires on %s. If you were not expecting it, you can ignore this email.</p>
                """.formatted(HtmlUtils.htmlEscape(inviter), HtmlUtils.htmlEscape(event.companyName()),
                HtmlUtils.htmlEscape(acceptLink), HtmlUtils.htmlEscape(expires));
        return new EmailMessage("invitation", event.email(), subject, text, html, event.companyId());
    }
}
