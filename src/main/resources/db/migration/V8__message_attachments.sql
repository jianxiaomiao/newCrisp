ALTER TABLE conversation_message
    ADD COLUMN attachment_name VARCHAR(180) NULL,
    ADD COLUMN attachment_type VARCHAR(32) CHARACTER SET ascii NULL,
    ADD COLUMN attachment_size INT NULL,
    ADD COLUMN attachment_sha BINARY(32) NULL;

CREATE TABLE conversation_attachment (
    tenant_id BIGINT UNSIGNED NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seq BIGINT NOT NULL,
    content MEDIUMBLOB NOT NULL,
    PRIMARY KEY (tenant_id, conversation_id, seq),
    FOREIGN KEY (tenant_id, conversation_id, seq) REFERENCES conversation_message (tenant_id, conversation_id, seq)
) ENGINE=InnoDB;
