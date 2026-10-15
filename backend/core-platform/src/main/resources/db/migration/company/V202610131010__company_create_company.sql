-- Company core record. Settings, contacts and lifecycle history are added with company management (Phase 4).
CREATE TABLE company (
    id           BINARY(16)   NOT NULL,
    code         VARCHAR(63)  NOT NULL,
    legal_name   VARCHAR(200) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    country      CHAR(2)      NOT NULL,
    tax_id       VARCHAR(64)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    created_by   BINARY(16)   NULL,
    updated_by   BINARY(16)   NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_company PRIMARY KEY (id),
    CONSTRAINT uk_company_code UNIQUE (code),
    CONSTRAINT ck_company_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    INDEX ix_company_status_created_at (status, created_at),
    INDEX ix_company_display_name (display_name)
);
