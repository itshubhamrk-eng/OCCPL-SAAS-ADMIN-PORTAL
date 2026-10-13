-- Plans (Phase 6). ACTIVE plans are immutable: changes are made on a copy.
CREATE TABLE subscription_plan (
    id             BINARY(16)    NOT NULL,
    code           VARCHAR(64)   NOT NULL,
    name           VARCHAR(200)  NOT NULL,
    description    VARCHAR(2000) NULL,
    status         VARCHAR(16)   NOT NULL,
    billing_period VARCHAR(16)   NOT NULL,
    trial_days     INT           NOT NULL DEFAULT 0,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    created_by     BINARY(16)    NULL,
    published_at   DATETIME(6)   NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_subscription_plan PRIMARY KEY (id),
    CONSTRAINT uk_subscription_plan_code UNIQUE (code),
    CONSTRAINT ck_subscription_plan_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_subscription_plan_period CHECK (billing_period IN ('MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUAL')),
    CONSTRAINT ck_subscription_plan_trial CHECK (trial_days BETWEEN 0 AND 365),
    INDEX ix_subscription_plan_status_name (status, name)
);

CREATE TABLE plan_price (
    plan_id      BINARY(16) NOT NULL,
    currency     CHAR(3)    NOT NULL,
    amount_minor BIGINT     NOT NULL,
    CONSTRAINT pk_plan_price PRIMARY KEY (plan_id, currency),
    CONSTRAINT ck_plan_price_amount CHECK (amount_minor >= 0),
    CONSTRAINT fk_plan_price_plan FOREIGN KEY (plan_id) REFERENCES subscription_plan (id) ON DELETE CASCADE
);

CREATE TABLE plan_product (
    plan_id    BINARY(16) NOT NULL,
    product_id BINARY(16) NOT NULL,
    CONSTRAINT pk_plan_product PRIMARY KEY (plan_id, product_id),
    CONSTRAINT fk_plan_product_plan FOREIGN KEY (plan_id) REFERENCES subscription_plan (id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_product_product FOREIGN KEY (product_id) REFERENCES product (id)
);

-- limit_value NULL = unlimited (LIMIT features); BOOLEAN features use enabled only.
CREATE TABLE plan_feature (
    plan_id     BINARY(16) NOT NULL,
    product_id  BINARY(16) NOT NULL,
    feature_id  BINARY(16) NOT NULL,
    enabled     BOOLEAN    NOT NULL,
    limit_value BIGINT     NULL,
    CONSTRAINT pk_plan_feature PRIMARY KEY (plan_id, feature_id),
    CONSTRAINT ck_plan_feature_limit CHECK (limit_value IS NULL OR limit_value >= 0),
    CONSTRAINT fk_plan_feature_plan FOREIGN KEY (plan_id) REFERENCES subscription_plan (id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_feature_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT fk_plan_feature_feature FOREIGN KEY (feature_id) REFERENCES product_feature (id)
);

CREATE TABLE company_subscription (
    id                   BINARY(16)  NOT NULL,
    company_id           BINARY(16)  NOT NULL,
    plan_id              BINARY(16)  NOT NULL,
    status               VARCHAR(16) NOT NULL,
    currency             CHAR(3)     NOT NULL,
    price_minor          BIGINT      NOT NULL,
    billing_period       VARCHAR(16) NOT NULL,
    current_period_start DATETIME(6) NOT NULL,
    current_period_end   DATETIME(6) NOT NULL,
    trial_end            DATETIME(6) NULL,
    cancel_at_period_end BOOLEAN     NOT NULL DEFAULT FALSE,
    pending_plan_id      BINARY(16)  NULL,
    status_changed_at    DATETIME(6) NOT NULL,
    cancelled_at         DATETIME(6) NULL,
    ended_at             DATETIME(6) NULL,
    data_retention_until DATETIME(6) NULL,
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    version              BIGINT      NOT NULL DEFAULT 0,
    -- One live (non-terminal) subscription per company and plan.
    live_key             BINARY(32) AS (IF(status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED'),
                                           CONCAT(company_id, plan_id), NULL)) STORED,
    CONSTRAINT pk_company_subscription PRIMARY KEY (id),
    CONSTRAINT uk_company_subscription_live UNIQUE (live_key),
    CONSTRAINT ck_company_subscription_status CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT fk_company_subscription_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_company_subscription_plan FOREIGN KEY (plan_id) REFERENCES subscription_plan (id),
    CONSTRAINT fk_company_subscription_pending_plan FOREIGN KEY (pending_plan_id) REFERENCES subscription_plan (id),
    INDEX ix_company_subscription_company_status (company_id, status),
    INDEX ix_company_subscription_status_period_end (status, current_period_end),
    INDEX ix_company_subscription_status_trial_end (status, trial_end),
    INDEX ix_company_subscription_status_changed (status, status_changed_at)
);

CREATE TABLE subscription_history (
    id              BINARY(16)   NOT NULL,
    subscription_id BINARY(16)   NOT NULL,
    event           VARCHAR(32)  NOT NULL,
    from_status     VARCHAR(16)  NULL,
    to_status       VARCHAR(16)  NOT NULL,
    from_plan_id    BINARY(16)   NULL,
    to_plan_id      BINARY(16)   NULL,
    actor_user_id   BINARY(16)   NULL,
    reason          VARCHAR(500) NULL,
    occurred_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_subscription_history PRIMARY KEY (id),
    CONSTRAINT fk_subscription_history_subscription FOREIGN KEY (subscription_id) REFERENCES company_subscription (id),
    INDEX ix_subscription_history_subscription (subscription_id, occurred_at)
);
