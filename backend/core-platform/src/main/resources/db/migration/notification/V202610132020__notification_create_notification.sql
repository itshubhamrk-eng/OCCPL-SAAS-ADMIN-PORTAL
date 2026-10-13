-- Transactional outbox for outgoing email. Bodies are cleared once sent (they may contain one-time links).
CREATE TABLE notification (
    id              BINARY(16)   NOT NULL,
    company_id      BINARY(16)   NULL,
    template        VARCHAR(64)  NOT NULL,
    recipient_email VARCHAR(320) NOT NULL,
    subject         VARCHAR(255) NOT NULL,
    body_text       MEDIUMTEXT   NULL,
    body_html       MEDIUMTEXT   NULL,
    status          VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)  NOT NULL,
    last_error      VARCHAR(500) NULL,
    created_at      DATETIME(6)  NOT NULL,
    sent_at         DATETIME(6)  NULL,
    CONSTRAINT pk_notification PRIMARY KEY (id),
    CONSTRAINT ck_notification_status CHECK (status IN ('QUEUED', 'SENT', 'FAILED')),
    INDEX ix_notification_due (status, next_attempt_at)
);
