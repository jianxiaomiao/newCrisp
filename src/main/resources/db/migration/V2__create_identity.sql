CREATE TABLE department (
    tenant_id BIGINT UNSIGNED NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(120) NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_department_code (tenant_id, code),
    FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE staff_account (
    tenant_id BIGINT UNSIGNED NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    password_hash VARCHAR(100) CHARACTER SET ascii NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_staff_email (tenant_id, email),
    FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE staff_tenant_role (
    tenant_id BIGINT UNSIGNED NOT NULL,
    staff_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_code VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (tenant_id, staff_id, role_code),
    CONSTRAINT ck_staff_tenant_role CHECK (role_code IN ('TENANT_ADMIN')),
    FOREIGN KEY (tenant_id, staff_id) REFERENCES staff_account (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE staff_session (
    token_hash BINARY(32) PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    staff_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    KEY ix_staff_session_expiry (expires_at),
    FOREIGN KEY (tenant_id, staff_id) REFERENCES staff_account (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE integration (
    tenant_id BIGINT UNSIGNED NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    department_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nexus_origin VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_integration_origin (tenant_id, nexus_origin),
    FOREIGN KEY (tenant_id, department_id) REFERENCES department (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE integration_credential (
    key_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    integration_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    secret_hash BINARY(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    FOREIGN KEY (tenant_id, integration_id) REFERENCES integration (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE customer (
    tenant_id BIGINT UNSIGNED NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    integration_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_user_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_customer_subject (tenant_id, integration_id, external_user_id),
    FOREIGN KEY (tenant_id, integration_id) REFERENCES integration (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE launch_grant (
    token_hash BINARY(32) PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    integration_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_user_id BIGINT NOT NULL,
    external_session_id BIGINT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3),
    KEY ix_launch_grant_expiry (expires_at),
    FOREIGN KEY (tenant_id, integration_id) REFERENCES integration (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE customer_session (
    token_hash BINARY(32) PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_session_id BIGINT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    verified_until DATETIME(3) NOT NULL,
    KEY ix_customer_session_expiry (expires_at),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;
