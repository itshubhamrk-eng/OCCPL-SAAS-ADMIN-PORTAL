-- Lead registration: prospective customers tracked by platform staff.
CREATE TABLE lead (
    id               BINARY(16)    NOT NULL,
    company_name     VARCHAR(200)  NOT NULL,
    contact_name     VARCHAR(200)  NOT NULL,
    email            VARCHAR(320)  NOT NULL,
    phone            VARCHAR(32)   NULL,
    product_interest VARCHAR(200)  NULL,
    source           VARCHAR(100)  NULL,
    status           VARCHAR(16)   NOT NULL,
    notes            VARCHAR(2000) NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    created_by       BINARY(16)    NULL,
    updated_by       BINARY(16)    NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_lead PRIMARY KEY (id),
    CONSTRAINT ck_lead_status CHECK (status IN ('NEW', 'CONTACTED', 'QUALIFIED', 'CONVERTED', 'LOST')),
    INDEX ix_lead_status_created (status, created_at),
    INDEX ix_lead_company_name (company_name),
    INDEX ix_lead_email (email)
);
