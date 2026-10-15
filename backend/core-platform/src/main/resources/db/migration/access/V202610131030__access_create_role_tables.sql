-- Permissions are a fixed catalogue owned by the code base (seeded by R__access_seed_*.sql).
CREATE TABLE permission (
    code        VARCHAR(100) NOT NULL,
    scope       VARCHAR(16)  NOT NULL,
    description VARCHAR(255) NOT NULL,
    CONSTRAINT pk_permission PRIMARY KEY (code),
    CONSTRAINT ck_permission_scope CHECK (scope IN ('PLATFORM', 'COMPANY'))
);

-- company_id NULL = system role available to every company (or a platform role).
CREATE TABLE role (
    id          BINARY(16)   NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    scope       VARCHAR(16)  NOT NULL,
    company_id  BINARY(16)   NULL,
    system_role BOOLEAN      NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255) NULL,
    company_key BINARY(16) AS (COALESCE(company_id, UNHEX('00000000000000000000000000000000'))) STORED,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT uk_role_company_code UNIQUE (company_key, code),
    CONSTRAINT ck_role_scope CHECK (scope IN ('PLATFORM', 'COMPANY')),
    CONSTRAINT ck_role_platform_is_global CHECK (scope = 'COMPANY' OR company_id IS NULL),
    CONSTRAINT fk_role_company FOREIGN KEY (company_id) REFERENCES company (id)
);

CREATE TABLE role_permission (
    role_id         BINARY(16)   NOT NULL,
    permission_code VARCHAR(100) NOT NULL,
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission_code),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_code) REFERENCES permission (code)
);

CREATE TABLE platform_role_assignment (
    user_id     BINARY(16)  NOT NULL,
    role_id     BINARY(16)  NOT NULL,
    assigned_at DATETIME(6) NOT NULL,
    assigned_by BINARY(16)  NULL,
    CONSTRAINT pk_platform_role_assignment PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_platform_role_assignment_user FOREIGN KEY (user_id) REFERENCES platform_user (id),
    CONSTRAINT fk_platform_role_assignment_role FOREIGN KEY (role_id) REFERENCES role (id),
    INDEX ix_platform_role_assignment_role (role_id)
);

CREATE TABLE membership_role (
    membership_id BINARY(16)  NOT NULL,
    role_id       BINARY(16)  NOT NULL,
    assigned_at   DATETIME(6) NOT NULL,
    assigned_by   BINARY(16)  NULL,
    CONSTRAINT pk_membership_role PRIMARY KEY (membership_id, role_id),
    CONSTRAINT fk_membership_role_membership FOREIGN KEY (membership_id) REFERENCES company_membership (id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_role_role FOREIGN KEY (role_id) REFERENCES role (id),
    INDEX ix_membership_role_role (role_id)
);
