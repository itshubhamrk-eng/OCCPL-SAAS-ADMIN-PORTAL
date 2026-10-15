-- Product catalogue (Phase 5). Products are independent services; the platform stores only their
-- registration, versioned integration configuration and the features plans can grant.
CREATE TABLE product (
    id                 BINARY(16)    NOT NULL,
    code               VARCHAR(50)   NOT NULL,
    name               VARCHAR(200)  NOT NULL,
    description        VARCHAR(2000) NULL,
    status             VARCHAR(16)   NOT NULL,
    app_url            VARCHAR(500)  NOT NULL,
    api_base_url       VARCHAR(500)  NULL,
    api_audience       VARCHAR(100)  NOT NULL,
    current_version_id BINARY(16)    NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    created_by         BINARY(16)    NULL,
    updated_by         BINARY(16)    NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_product PRIMARY KEY (id),
    CONSTRAINT uk_product_code UNIQUE (code),
    CONSTRAINT uk_product_api_audience UNIQUE (api_audience),
    CONSTRAINT ck_product_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    INDEX ix_product_status_name (status, name),
    INDEX ix_product_name (name)
);

-- Configuration versions are immutable once created; publishing makes one the product's current version.
CREATE TABLE product_version (
    id             BINARY(16)    NOT NULL,
    product_id     BINARY(16)    NOT NULL,
    version_no     INT           NOT NULL,
    schema_version INT           NOT NULL,
    config_json    JSON          NOT NULL,
    config_hash    CHAR(64)      NOT NULL,
    status         VARCHAR(16)   NOT NULL,
    notes          VARCHAR(1000) NULL,
    created_at     DATETIME(6)   NOT NULL,
    created_by     BINARY(16)    NULL,
    published_at   DATETIME(6)   NULL,
    published_by   BINARY(16)    NULL,
    CONSTRAINT pk_product_version PRIMARY KEY (id),
    CONSTRAINT uk_product_version_no UNIQUE (product_id, version_no),
    CONSTRAINT ck_product_version_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT fk_product_version_product FOREIGN KEY (product_id) REFERENCES product (id)
);

ALTER TABLE product
    ADD CONSTRAINT fk_product_current_version FOREIGN KEY (current_version_id) REFERENCES product_version (id);

CREATE TABLE product_feature (
    id           BINARY(16)    NOT NULL,
    product_id   BINARY(16)    NOT NULL,
    code         VARCHAR(64)   NOT NULL,
    name         VARCHAR(200)  NOT NULL,
    description  VARCHAR(1000) NULL,
    type         VARCHAR(16)   NOT NULL,
    unit         VARCHAR(32)   NULL,
    reset_period VARCHAR(16)   NULL,
    status       VARCHAR(16)   NOT NULL,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL,
    version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_product_feature PRIMARY KEY (id),
    CONSTRAINT uk_product_feature_code UNIQUE (product_id, code),
    CONSTRAINT ck_product_feature_type CHECK (type IN ('BOOLEAN', 'LIMIT')),
    CONSTRAINT ck_product_feature_unit CHECK (unit IS NULL OR unit IN
        ('SEATS', 'STORAGE_GB', 'RECORDS', 'API_CALLS', 'TRANSACTIONS', 'DOCUMENTS', 'MESSAGES', 'COUNT')),
    CONSTRAINT ck_product_feature_reset CHECK (reset_period IS NULL OR reset_period IN
        ('NONE', 'DAILY', 'MONTHLY', 'BILLING_PERIOD')),
    CONSTRAINT ck_product_feature_shape CHECK (
        (type = 'BOOLEAN' AND unit IS NULL AND reset_period IS NULL)
        OR (type = 'LIMIT' AND unit IS NOT NULL AND reset_period IS NOT NULL)),
    CONSTRAINT ck_product_feature_status CHECK (status IN ('ACTIVE', 'DEPRECATED')),
    CONSTRAINT fk_product_feature_product FOREIGN KEY (product_id) REFERENCES product (id)
);
