ALTER TABLE conversation
    ADD COLUMN first_staff_reply_at DATETIME(3) NULL,
    ADD KEY ix_conversation_analytics (tenant_id, department_id, queued_at);

CREATE TABLE conversation_rating (
    tenant_id BIGINT UNSIGNED NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    score TINYINT NOT NULL,
    comment VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, conversation_id),
    CONSTRAINT ck_rating_score CHECK (score BETWEEN 1 AND 5),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;

ALTER TABLE conversation_message
    ADD COLUMN attachment_storage_key VARCHAR(255) CHARACTER SET ascii NULL;
