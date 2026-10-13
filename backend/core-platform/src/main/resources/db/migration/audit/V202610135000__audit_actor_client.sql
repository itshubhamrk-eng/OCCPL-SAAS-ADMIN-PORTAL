-- Phase 7: actions performed by product services (client-credentials tokens) record the client id.
ALTER TABLE audit_log
    ADD COLUMN actor_client_id VARCHAR(100) NULL AFTER actor_user_id;
