CREATE TABLE guest_site (
    tenant_id BIGINT UNSIGNED NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    department_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(80) NOT NULL,
    allowed_origin VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_guest_site_id (id),
    FOREIGN KEY (tenant_id, department_id) REFERENCES department (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;

ALTER TABLE customer
    MODIFY integration_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY external_user_id BIGINT NULL,
    ADD COLUMN guest_site_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD FOREIGN KEY (tenant_id, guest_site_id) REFERENCES guest_site (tenant_id, id),
    ADD CHECK ((guest_site_id IS NULL AND integration_id IS NOT NULL AND external_user_id IS NOT NULL)
            OR (guest_site_id IS NOT NULL AND integration_id IS NULL AND external_user_id IS NULL));

CREATE TABLE guest_resume (
    token_hash BINARY(32) PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    site_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    linked_at DATETIME(3) NULL,
    KEY ix_guest_creation (tenant_id, site_id, created_at),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer (tenant_id, id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES guest_site (tenant_id, id)
) ENGINE=InnoDB;
