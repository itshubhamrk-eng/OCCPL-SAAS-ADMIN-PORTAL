-- Append-only record of administrative actions (SECURITY.md §6). Rows are never updated or deleted
-- by the application; retention/archival is handled by a separate operational process.
CREATE TABLE audit_log (
    id             BINARY(16)   NOT NULL,
    occurred_at    DATETIME(6)  NOT NULL,
    actor_type     VARCHAR(16)  NOT NULL,
    actor_user_id  BINARY(16)   NULL,
    company_id     BINARY(16)   NULL,
    action         VARCHAR(100) NOT NULL,
    target_type    VARCHAR(64)  NOT NULL,
    target_id      VARCHAR(64)  NULL,
    outcome        VARCHAR(16)  NOT NULL,
    correlation_id VARCHAR(64)  NULL,
    details        JSON         NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_audit_log_actor_type CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM')),
    CONSTRAINT ck_audit_log_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    INDEX ix_audit_log_company_occurred (company_id, occurred_at),
    INDEX ix_audit_log_actor_occurred (actor_user_id, occurred_at),
    INDEX ix_audit_log_target (target_type, target_id),
    INDEX ix_audit_log_occurred (occurred_at)
);
