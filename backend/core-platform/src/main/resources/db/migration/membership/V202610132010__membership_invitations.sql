-- Only the SHA-256 hash of an invitation token is stored; the token itself exists only in the email.
CREATE TABLE invitation (
    id            BINARY(16)   NOT NULL,
    company_id    BINARY(16)   NOT NULL,
    membership_id BINARY(16)   NOT NULL,
    email         VARCHAR(320) NOT NULL,
    token_hash    CHAR(64)     NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    invited_by    BINARY(16)   NULL,
    accepted_at   DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    -- At most one PENDING invitation per membership (NULLs are not unique in MySQL).
    pending_key   BINARY(16) AS (IF(status = 'PENDING', membership_id, NULL)) STORED,
    CONSTRAINT pk_invitation PRIMARY KEY (id),
    CONSTRAINT uk_invitation_token_hash UNIQUE (token_hash),
    CONSTRAINT uk_invitation_pending UNIQUE (pending_key),
    CONSTRAINT ck_invitation_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT fk_invitation_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_invitation_membership FOREIGN KEY (membership_id) REFERENCES company_membership (id),
    INDEX ix_invitation_company_status (company_id, status, created_at),
    INDEX ix_invitation_status_expires (status, expires_at)
);

-- Member listings: WHERE company_id = ? [AND status = ?] ORDER BY created_at, id.
CREATE INDEX ix_company_membership_company_status_created ON company_membership (company_id, status, created_at, id);
