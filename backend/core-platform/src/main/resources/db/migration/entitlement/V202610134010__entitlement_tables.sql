-- Derived from live subscriptions; fully recomputed per company whenever a subscription changes.
CREATE TABLE company_product_entitlement (
    id          BINARY(16)  NOT NULL,
    company_id  BINARY(16)  NOT NULL,
    product_id  BINARY(16)  NOT NULL,
    status      VARCHAR(16) NOT NULL,
    valid_until DATETIME(6) NULL,
    computed_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_company_product_entitlement PRIMARY KEY (id),
    CONSTRAINT uk_company_product_entitlement UNIQUE (company_id, product_id),
    CONSTRAINT ck_company_product_entitlement_status CHECK (status IN ('ACTIVE', 'GRACE', 'SUSPENDED')),
    CONSTRAINT fk_cpe_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_cpe_product FOREIGN KEY (product_id) REFERENCES product (id)
);

CREATE TABLE entitlement_feature (
    entitlement_id BINARY(16)  NOT NULL,
    feature_id     BINARY(16)  NOT NULL,
    enabled        BOOLEAN     NOT NULL,
    limit_value    BIGINT      NULL,
    -- Start of the current BILLING_PERIOD usage window (from the granting subscription).
    period_anchor  DATETIME(6) NULL,
    CONSTRAINT pk_entitlement_feature PRIMARY KEY (entitlement_id, feature_id),
    CONSTRAINT fk_entitlement_feature_entitlement FOREIGN KEY (entitlement_id) REFERENCES company_product_entitlement (id) ON DELETE CASCADE,
    CONSTRAINT fk_entitlement_feature_feature FOREIGN KEY (feature_id) REFERENCES product_feature (id)
);

-- Stock features use period_start = 1970-01-01; flow features one row per reset window.
CREATE TABLE usage_counter (
    company_id   BINARY(16)  NOT NULL,
    feature_id   BINARY(16)  NOT NULL,
    period_start DATETIME(6) NOT NULL,
    used         BIGINT      NOT NULL DEFAULT 0,
    updated_at   DATETIME(6) NOT NULL,
    CONSTRAINT pk_usage_counter PRIMARY KEY (company_id, feature_id, period_start),
    CONSTRAINT ck_usage_counter_used CHECK (used >= 0),
    CONSTRAINT fk_usage_counter_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_usage_counter_feature FOREIGN KEY (feature_id) REFERENCES product_feature (id)
);

-- Append-only usage events; the idempotency key makes retried consume/release calls safe.
CREATE TABLE usage_record (
    id              BINARY(16)   NOT NULL,
    company_id      BINARY(16)   NOT NULL,
    feature_id      BINARY(16)   NOT NULL,
    quantity        BIGINT       NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    period_start    DATETIME(6)  NOT NULL,
    used_after      BIGINT       NOT NULL,
    recorded_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_usage_record PRIMARY KEY (id),
    CONSTRAINT uk_usage_record_idempotency UNIQUE (company_id, idempotency_key),
    CONSTRAINT fk_usage_record_feature FOREIGN KEY (feature_id) REFERENCES product_feature (id),
    INDEX ix_usage_record_company_recorded (company_id, recorded_at)
);
