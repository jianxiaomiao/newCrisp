CREATE TABLE conversation_note (
    tenant_id BIGINT UNSIGNED NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    staff_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body VARCHAR(4000) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, id),
    KEY ix_note_conversation (tenant_id, conversation_id, created_at),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id),
    FOREIGN KEY (tenant_id, staff_id) REFERENCES staff_account (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;

CREATE TABLE conversation_tag (
    tenant_id BIGINT UNSIGNED NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(32) NOT NULL,
    color VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT '#409eff',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_tag_name (tenant_id, name),
    FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;

CREATE TABLE conversation_tag_rel (
    tenant_id BIGINT UNSIGNED NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tag_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, conversation_id, tag_id),
    KEY ix_tag_rel_tag (tenant_id, tag_id),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id),
    FOREIGN KEY (tenant_id, tag_id) REFERENCES conversation_tag (tenant_id, id)
) ENGINE=InnoDB;

ALTER TABLE quick_reply
    ADD COLUMN owner_staff_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD FOREIGN KEY (tenant_id, owner_staff_id) REFERENCES staff_account (tenant_id, id);

ALTER TABLE tenant
    ADD COLUMN welcome_message VARCHAR(1000) NULL,
    ADD COLUMN auto_close_minutes INT NULL;

CREATE TABLE conversation_auto_close (
    tenant_id BIGINT UNSIGNED NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_version BIGINT NOT NULL,
    reason VARCHAR(200) NOT NULL,
    closed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, conversation_id, expected_version),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;

ALTER TABLE conversation_message
    ADD COLUMN system_message BOOLEAN NOT NULL DEFAULT FALSE;

SET @old_chk = (
    SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation_message' AND CONSTRAINT_TYPE = 'CHECK'
    LIMIT 1
);
SET @drop_sql = IF(@old_chk IS NOT NULL, CONCAT('ALTER TABLE conversation_message DROP CHECK ', @old_chk), 'SELECT 1');
PREPARE stmt FROM @drop_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE conversation_message
    ADD CONSTRAINT ck_message_sender CHECK (
        (system_message = TRUE AND customer_sender_id IS NULL AND staff_sender_id IS NULL)
        OR (system_message = FALSE AND ((customer_sender_id IS NULL) <> (staff_sender_id IS NULL)))
    );
