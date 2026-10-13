package com.occpl.platform.audit.api;

/**
 * Records audit events in the caller's transaction, so an action and its audit row commit or roll
 * back together. The actor and correlation id are taken from the current request.
 */
public interface AuditLogger {

    void record(AuditEvent event);

    /** For background/system actions without an authenticated user. */
    void recordSystem(AuditEvent event);
}
