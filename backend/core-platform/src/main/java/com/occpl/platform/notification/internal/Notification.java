package com.occpl.platform.notification.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification")
class Notification {

    /** Retry delays after attempt 1, 2, …; the last value repeats. */
    private static final Duration[] BACKOFF = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(4)};

    @Id
    private UUID id;

    @Column(name = "company_id", updatable = false)
    private UUID companyId;

    @Column(nullable = false, updatable = false)
    private String template;

    @Column(name = "recipient_email", nullable = false, updatable = false)
    private String recipientEmail;

    @Column(nullable = false, updatable = false)
    private String subject;

    @Column(name = "body_text", columnDefinition = "mediumtext")
    private String bodyText;

    @Column(name = "body_html", columnDefinition = "mediumtext")
    private String bodyHtml;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {
    }

    Notification(UUID id, UUID companyId, String template, String recipientEmail, String subject, String bodyText,
            String bodyHtml, Instant now) {
        this.id = id;
        this.companyId = companyId;
        this.template = template;
        this.recipientEmail = recipientEmail;
        this.subject = subject;
        this.bodyText = bodyText;
        this.bodyHtml = bodyHtml;
        this.status = "QUEUED";
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    /** Bodies are dropped once sent: they may contain one-time links such as invitation tokens. */
    void markSent(Instant now) {
        status = "SENT";
        attempts++;
        sentAt = now;
        lastError = null;
        bodyText = null;
        bodyHtml = null;
    }

    /** Returns true when no further attempts will be made. */
    boolean markFailed(String error, int maxAttempts, Instant now) {
        attempts++;
        lastError = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500));
        if (attempts >= maxAttempts) {
            status = "FAILED";
            bodyText = null;
            bodyHtml = null;
            return true;
        }
        nextAttemptAt = now.plus(BACKOFF[Math.min(attempts - 1, BACKOFF.length - 1)]);
        return false;
    }

    UUID id() { return id; }
    String template() { return template; }
    String recipientEmail() { return recipientEmail; }
    String subject() { return subject; }
    String bodyText() { return bodyText; }
    String bodyHtml() { return bodyHtml; }
    int attempts() { return attempts; }
}
