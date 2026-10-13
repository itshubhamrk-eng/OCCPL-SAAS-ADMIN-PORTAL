CREATE TABLE company_membership (
    id         BINARY(16)  NOT NULL,
    company_id BINARY(16)  NOT NULL,
    user_id    BINARY(16)  NOT NULL,
    status     VARCHAR(32) NOT NULL,
    joined_at  DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_company_membership PRIMARY KEY (id),
    CONSTRAINT uk_company_membership_company_user UNIQUE (company_id, user_id),
    CONSTRAINT ck_company_membership_status CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    CONSTRAINT fk_company_membership_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_company_membership_user FOREIGN KEY (user_id) REFERENCES platform_user (id),
    INDEX ix_company_membership_user_status (user_id, status)
);
