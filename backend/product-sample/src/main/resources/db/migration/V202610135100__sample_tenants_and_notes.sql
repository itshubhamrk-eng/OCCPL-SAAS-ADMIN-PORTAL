-- Sample product schema. The product owns this database; Core never reads or writes it.
CREATE TABLE tenant (
    company_id     CHAR(36)     NOT NULL,
    company_code   VARCHAR(50)  NOT NULL,
    company_name   VARCHAR(200) NULL,
    status         VARCHAR(16)  NOT NULL,
    last_job_id    CHAR(36)     NULL,
    provisioned_at DATETIME(6)  NOT NULL,
    removed_at     DATETIME(6)  NULL,
    CONSTRAINT pk_tenant PRIMARY KEY (company_id),
    CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE', 'REMOVED'))
);

CREATE TABLE note (
    id         CHAR(36)      NOT NULL,
    company_id CHAR(36)      NOT NULL,
    title      VARCHAR(200)  NOT NULL,
    body       VARCHAR(4000) NULL,
    created_by VARCHAR(255)  NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    CONSTRAINT pk_note PRIMARY KEY (id),
    CONSTRAINT fk_note_tenant FOREIGN KEY (company_id) REFERENCES tenant (company_id),
    INDEX ix_note_company_created (company_id, created_at)
);
