-- Billing (D-3/D-4): invoices are created and numbered by the payment gateway (Razorpay), which also
-- emails them and collects payment. Core keeps references, amounts and states. Tax (GST) treatment is
-- an open decision (PROJECT.md §5): today the plan price is the invoice amount.

CREATE TABLE invoice (
    id                  BINARY(16)    NOT NULL,
    company_id          BINARY(16)    NOT NULL,
    subscription_id     BINARY(16)    NOT NULL,
    gateway             VARCHAR(16)   NOT NULL,
    gateway_invoice_id  VARCHAR(64)   NULL,
    gateway_number      VARCHAR(64)   NULL,
    status              VARCHAR(20)   NOT NULL,
    description         VARCHAR(500)  NOT NULL,
    currency            CHAR(3)       NOT NULL,
    amount_minor        BIGINT        NOT NULL,
    amount_paid_minor   BIGINT        NOT NULL DEFAULT 0,
    period_start        DATETIME(6)   NOT NULL,
    period_end          DATETIME(6)   NOT NULL,
    payment_url         VARCHAR(500)  NULL,
    customer_email      VARCHAR(320)  NOT NULL,
    issued_at           DATETIME(6)   NULL,
    expires_at          DATETIME(6)   NULL,
    paid_at             DATETIME(6)   NULL,
    payment_applied     VARCHAR(20)   NULL,
    last_error          VARCHAR(1000) NULL,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    created_by          BINARY(16)    NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    -- At most one open invoice per subscription and period (cancelled/expired/failed ones may repeat).
    open_key            VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN status IN ('CREATING', 'ISSUED', 'PARTIALLY_PAID', 'PAID')
             THEN CONCAT(HEX(subscription_id), '/', DATE_FORMAT(period_start, '%Y%m%d%H%i%s%f')) END) STORED,
    CONSTRAINT pk_invoice PRIMARY KEY (id),
    CONSTRAINT uk_invoice_gateway_id UNIQUE (gateway, gateway_invoice_id),
    CONSTRAINT uk_invoice_open UNIQUE (open_key),
    CONSTRAINT ck_invoice_gateway CHECK (gateway IN ('RAZORPAY')),
    CONSTRAINT ck_invoice_status CHECK (status IN ('CREATING', 'ISSUE_FAILED', 'ISSUED', 'PARTIALLY_PAID', 'PAID',
        'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_invoice_amounts CHECK (amount_minor > 0 AND amount_paid_minor >= 0),
    CONSTRAINT ck_invoice_applied CHECK (payment_applied IS NULL OR payment_applied IN ('APPLIED', 'ALREADY_COVERED',
        'NOT_APPLICABLE')),
    CONSTRAINT fk_invoice_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_invoice_subscription FOREIGN KEY (subscription_id) REFERENCES company_subscription (id),
    INDEX ix_invoice_company_created (company_id, created_at),
    INDEX ix_invoice_subscription (subscription_id, period_start),
    INDEX ix_invoice_status_created (status, created_at)
);

-- Payments reported by the gateway (one row per gateway payment id and outcome).
CREATE TABLE payment_transaction (
    id                 BINARY(16)   NOT NULL,
    invoice_id         BINARY(16)   NOT NULL,
    company_id         BINARY(16)   NOT NULL,
    gateway_payment_id VARCHAR(64)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    amount_minor       BIGINT       NOT NULL,
    currency           CHAR(3)      NOT NULL,
    method             VARCHAR(32)  NULL,
    error_reason       VARCHAR(500) NULL,
    occurred_at        DATETIME(6)  NOT NULL,
    CONSTRAINT pk_payment_transaction PRIMARY KEY (id),
    CONSTRAINT uk_payment_transaction UNIQUE (gateway_payment_id, status),
    CONSTRAINT ck_payment_transaction_status CHECK (status IN ('CAPTURED', 'FAILED')),
    CONSTRAINT fk_payment_transaction_invoice FOREIGN KEY (invoice_id) REFERENCES invoice (id),
    INDEX ix_payment_transaction_invoice (invoice_id, occurred_at),
    INDEX ix_payment_transaction_company (company_id, occurred_at)
);

-- Every verified webhook delivery, by the gateway's event id (duplicates are acknowledged and ignored).
-- Only a SHA-256 of the raw body is kept, never the payload itself (SECURITY.md §4).
CREATE TABLE payment_webhook_event (
    gateway      VARCHAR(16)  NOT NULL,
    event_id     VARCHAR(100) NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    body_sha256  CHAR(64)     NOT NULL,
    outcome      VARCHAR(32)  NOT NULL,
    received_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_payment_webhook_event PRIMARY KEY (gateway, event_id),
    INDEX ix_payment_webhook_event_received (received_at)
);
