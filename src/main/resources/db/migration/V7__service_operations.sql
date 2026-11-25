ALTER TABLE staff_account ADD COLUMN accepting BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE tenant
    ADD COLUMN assignment_limit INT NULL,
    ADD COLUMN dispatch_waiting BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN hours_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    ADD COLUMN business_hours JSON NULL;

CREATE TABLE quick_reply (
    tenant_id BIGINT UNSIGNED NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(80) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;

CREATE INDEX ix_conversation_waiting ON conversation (tenant_id, status, queued_at, id);

ALTER TABLE conversation_auto_assignment
    ADD COLUMN expected_version BIGINT NOT NULL DEFAULT 0,
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (tenant_id, conversation_id, expected_version);

ALTER TABLE chat_outbox ADD COLUMN assignment_version BIGINT NULL;
