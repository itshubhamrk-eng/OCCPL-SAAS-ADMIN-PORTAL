-- Platform users are global (not owned by a company) and map to one or more IdP identities.
-- `user` is avoided as a table name because USER is a MySQL keyword.
CREATE TABLE platform_user (
    id            BINARY(16)   NOT NULL,
    email         VARCHAR(320) NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    last_login_at DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_platform_user PRIMARY KEY (id),
    CONSTRAINT uk_platform_user_email UNIQUE (email),
    CONSTRAINT ck_platform_user_status CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED'))
);

CREATE TABLE user_identity (
    id         BINARY(16)   NOT NULL,
    user_id    BINARY(16)   NOT NULL,
    provider   VARCHAR(64)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_user_identity PRIMARY KEY (id),
    CONSTRAINT uk_user_identity_provider_subject UNIQUE (provider, subject),
    CONSTRAINT uk_user_identity_user_provider UNIQUE (user_id, provider),
    CONSTRAINT fk_user_identity_user FOREIGN KEY (user_id) REFERENCES platform_user (id)
);

CREATE TABLE user_profile (
    user_id      BINARY(16)   NOT NULL,
    display_name VARCHAR(200) NULL,
    phone        VARCHAR(32)  NULL,
    locale       VARCHAR(16)  NULL,
    time_zone    VARCHAR(64)  NULL,
    updated_at   DATETIME(6)  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_profile PRIMARY KEY (user_id),
    CONSTRAINT fk_user_profile_user FOREIGN KEY (user_id) REFERENCES platform_user (id)
);
