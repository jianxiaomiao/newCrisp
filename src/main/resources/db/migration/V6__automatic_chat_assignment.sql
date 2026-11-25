CREATE TABLE chat_routing_lock (
    tenant_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE=InnoDB;

INSERT INTO chat_routing_lock (tenant_id) SELECT id FROM tenant;

CREATE TABLE conversation_auto_assignment (
    tenant_id BIGINT UNSIGNED NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    staff_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assigned_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, conversation_id),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id),
    FOREIGN KEY (tenant_id, staff_id) REFERENCES staff_account (tenant_id, id)
) ENGINE=InnoDB;
