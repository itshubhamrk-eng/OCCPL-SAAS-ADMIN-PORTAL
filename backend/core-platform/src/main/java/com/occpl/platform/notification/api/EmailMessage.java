package com.occpl.platform.notification.api;

import java.util.Objects;
import java.util.UUID;

/**
 * A rendered email. {@code htmlBody} is optional; callers must HTML-escape any user-provided values.
 *
 * @param template short identifier for metrics and support (e.g. {@code invitation})
 */
public record EmailMessage(String template, String to, String subject, String textBody, String htmlBody,
        UUID companyId) {

    public EmailMessage {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(textBody, "textBody");
    }
}
