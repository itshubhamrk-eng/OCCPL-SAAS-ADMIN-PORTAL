package com.occpl.platform.notification.api;

/**
 * Queues outgoing email in the caller's transaction (transactional outbox): the message is sent only
 * if the business change commits, and is retried until delivered or permanently failed.
 */
public interface Notifications {

    void enqueue(EmailMessage message);
}
