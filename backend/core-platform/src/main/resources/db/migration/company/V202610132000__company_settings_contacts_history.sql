CREATE TABLE company_settings (
    company_id            BINARY(16)   NOT NULL,
    time_zone             VARCHAR(64)  NOT NULL,
    locale                VARCHAR(16)  NOT NULL,
    -- Comma-separated lower-case domains; empty means any domain may be invited.
    allowed_email_domains VARCHAR(1000) NOT NULL DEFAULT '',
    updated_at            DATETIME(6)  NOT NULL,
    updated_by            BINARY(16)   NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_company_settings PRIMARY KEY (company_id),
    CONSTRAINT fk_company_settings_company FOREIGN KEY (company_id) REFERENCES company (id)
);

CREATE TABLE company_contact (
    id         BINARY(16)   NOT NULL,
    company_id BINARY(16)   NOT NULL,
    type       VARCHAR(16)  NOT NULL,
    name       VARCHAR(200) NOT NULL,
    email      VARCHAR(320) NOT NULL,
    phone      VARCHAR(32)  NULL,
    updated_at DATETIME(6)  NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_company_contact PRIMARY KEY (id),
    CONSTRAINT uk_company_contact_company_type UNIQUE (company_id, type),
    CONSTRAINT ck_company_contact_type CHECK (type IN ('PRIMARY', 'BILLING', 'TECHNICAL')),
    CONSTRAINT fk_company_contact_company FOREIGN KEY (company_id) REFERENCES company (id)
);

CREATE TABLE company_status_history (
    id          BINARY(16)   NOT NULL,
    company_id  BINARY(16)   NOT NULL,
    from_status VARCHAR(32)  NULL,
    to_status   VARCHAR(32)  NOT NULL,
    reason      VARCHAR(500) NULL,
    changed_by  BINARY(16)   NULL,
    changed_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_company_status_history PRIMARY KEY (id),
    CONSTRAINT fk_company_status_history_company FOREIGN KEY (company_id) REFERENCES company (id),
    INDEX ix_company_status_history_company (company_id, changed_at)
);

-- Prefix search on the company code (display name already has an index).
CREATE INDEX ix_company_code_prefix ON company (code, id);
