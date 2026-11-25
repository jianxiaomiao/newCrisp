ALTER TABLE staff_account ADD COLUMN display_name VARCHAR(64) NOT NULL DEFAULT '客服';

CREATE TABLE staff_department_role (
    tenant_id BIGINT UNSIGNED NOT NULL,
    department_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    staff_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_code VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (tenant_id, staff_id, department_id, role_code),
    CHECK (role_code IN ('AGENT', 'SUPERVISOR')),
    FOREIGN KEY (tenant_id, department_id) REFERENCES department (tenant_id, id),
    FOREIGN KEY (tenant_id, staff_id) REFERENCES staff_account (tenant_id, id)
) ENGINE=InnoDB;

CREATE TABLE conversation (
    tenant_id BIGINT UNSIGNED NOT NULL,
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    department_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assignee_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN status <> 'CLOSED' THEN 1 ELSE NULL END) STORED,
    state_version BIGINT NOT NULL DEFAULT 0,
    last_seq BIGINT NOT NULL DEFAULT 0,
    last_customer_seq BIGINT NOT NULL DEFAULT 0,
    last_staff_seq BIGINT NOT NULL DEFAULT 0,
    customer_read_seq BIGINT NOT NULL DEFAULT 0,
    staff_read_seq BIGINT NOT NULL DEFAULT 0,
    queued_at DATETIME(3) NOT NULL,
    last_activity_at DATETIME(3) NOT NULL,
    closed_at DATETIME(3),
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_conversation_active (tenant_id, customer_id, active_marker),
    KEY ix_conversation_queue (tenant_id, department_id, status, queued_at, id),
    KEY ix_conversation_owner (tenant_id, assignee_id, status, last_activity_at, id),
    KEY ix_conversation_customer (tenant_id, customer_id, last_activity_at, id),
    CHECK ((status = 'WAITING' AND assignee_id IS NULL AND closed_at IS NULL)
        OR (status = 'ACTIVE' AND assignee_id IS NOT NULL AND closed_at IS NULL)
        OR (status = 'CLOSED' AND assignee_id IS NOT NULL AND closed_at IS NOT NULL)),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer (tenant_id, id),
    FOREIGN KEY (tenant_id, department_id) REFERENCES department (tenant_id, id),
    FOREIGN KEY (tenant_id, assignee_id) REFERENCES staff_account (tenant_id, id)
) ENGINE=InnoDB;

CREATE TABLE conversation_message (
    tenant_id BIGINT UNSIGNED NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seq BIGINT NOT NULL,
    customer_sender_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    staff_sender_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    client_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    first_send BOOLEAN NOT NULL,
    body TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, conversation_id, seq),
    UNIQUE KEY uk_message_customer_request (tenant_id, customer_sender_id, client_message_id),
    UNIQUE KEY uk_message_staff_request (tenant_id, staff_sender_id, client_message_id),
    CHECK ((customer_sender_id IS NULL) <> (staff_sender_id IS NULL)),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_sender_id) REFERENCES customer (tenant_id, id),
    FOREIGN KEY (tenant_id, staff_sender_id) REFERENCES staff_account (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;

CREATE TABLE conversation_action (
    tenant_id BIGINT UNSIGNED NOT NULL,
    staff_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_version BIGINT NOT NULL,
    observed_customer_seq BIGINT,
    reason VARCHAR(400),
    previous_assignee CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    new_assignee CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_id, staff_id, action_id),
    KEY ix_action_history (tenant_id, conversation_id, expected_version),
    FOREIGN KEY (tenant_id, staff_id) REFERENCES staff_account (tenant_id, id),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id),
    FOREIGN KEY (tenant_id, previous_assignee) REFERENCES staff_account (tenant_id, id),
    FOREIGN KEY (tenant_id, new_assignee) REFERENCES staff_account (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;

CREATE TABLE chat_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_seq BIGINT,
    next_at DATETIME(3) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    KEY ix_chat_outbox_due (next_at, id),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id)
) ENGINE=InnoDB;
