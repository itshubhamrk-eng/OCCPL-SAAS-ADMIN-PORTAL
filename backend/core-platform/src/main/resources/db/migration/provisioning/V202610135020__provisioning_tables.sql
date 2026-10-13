-- Phase 7: setting up (and, after the retention period, removing) companies in product services.

-- One row per company and product the platform has ever provisioned or tried to provision.
CREATE TABLE company_product_provisioning (
    company_id      BINARY(16)  NOT NULL,
    product_id      BINARY(16)  NOT NULL,
    state           VARCHAR(24) NOT NULL,
    last_job_id     BINARY(16)  NULL,
    provisioned_at  DATETIME(6) NULL,
    deprovision_at  DATETIME(6) NULL,
    updated_at      DATETIME(6) NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_company_product_provisioning PRIMARY KEY (company_id, product_id),
    CONSTRAINT ck_cpp_state CHECK (state IN ('PROVISIONING', 'PROVISIONED', 'FAILED', 'DEPROVISION_SCHEDULED',
        'DEPROVISIONING', 'DEPROVISIONED')),
    CONSTRAINT fk_cpp_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_cpp_product FOREIGN KEY (product_id) REFERENCES product (id)
);

-- Work queue. Runners on any instance claim due jobs with SELECT … FOR UPDATE SKIP LOCKED and a lease
-- (locked_until); a job whose lease expired (crashed instance) is claimed again.
CREATE TABLE provisioning_job (
    id                BINARY(16)    NOT NULL,
    company_id        BINARY(16)    NOT NULL,
    product_id        BINARY(16)    NOT NULL,
    operation         VARCHAR(16)   NOT NULL,
    status            VARCHAR(24)   NOT NULL,
    attempts          INT           NOT NULL DEFAULT 0,
    max_attempts      INT           NOT NULL,
    next_attempt_at   DATETIME(6)   NULL,
    locked_until      DATETIME(6)   NULL,
    callback_deadline DATETIME(6)   NULL,
    config_version_no INT           NULL,
    last_http_status  INT           NULL,
    last_error        VARCHAR(1000) NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    completed_at      DATETIME(6)   NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_provisioning_job PRIMARY KEY (id),
    CONSTRAINT ck_provisioning_job_operation CHECK (operation IN ('PROVISION', 'DEPROVISION')),
    CONSTRAINT ck_provisioning_job_status CHECK (status IN ('PENDING', 'RUNNING', 'AWAITING_CALLBACK', 'SUCCEEDED',
        'FAILED', 'CANCELLED')),
    CONSTRAINT fk_provisioning_job_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_provisioning_job_product FOREIGN KEY (product_id) REFERENCES product (id),
    INDEX ix_provisioning_job_due (status, next_attempt_at),
    INDEX ix_provisioning_job_lease (status, locked_until),
    INDEX ix_provisioning_job_callback (status, callback_deadline),
    INDEX ix_provisioning_job_company_product (company_id, product_id, created_at),
    INDEX ix_provisioning_job_created (created_at)
);
