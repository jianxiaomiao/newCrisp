CREATE TABLE email_verification_code (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    purpose VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    code_hash BINARY(32) NOT NULL,
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    remote_ip VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3) NULL,
    KEY ix_email_verify_lookup (email, purpose, created_at),
    KEY ix_email_verify_ip (remote_ip, created_at),
    KEY ix_email_verify_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;
