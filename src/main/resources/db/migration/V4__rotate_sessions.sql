ALTER TABLE staff_session
    ADD COLUMN sid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN revoked_at DATETIME(3),
    ADD UNIQUE KEY uk_staff_session_sid (sid);

ALTER TABLE customer_session
    ADD COLUMN sid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN revoked_at DATETIME(3),
    ADD UNIQUE KEY uk_customer_session_sid (sid);

CREATE TABLE refresh_token (
    token_hash BINARY(32) PRIMARY KEY,
    staff_sid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    customer_sid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    bootstrap BOOLEAN NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    used_at DATETIME(3),
    KEY ix_refresh_expiry (expires_at),
    CHECK ((staff_sid IS NULL) <> (customer_sid IS NULL)),
    FOREIGN KEY (staff_sid) REFERENCES staff_session (sid),
    FOREIGN KEY (customer_sid) REFERENCES customer_session (sid)
) ENGINE=InnoDB;

CREATE TABLE websocket_ticket (
    token_hash BINARY(32) PRIMARY KEY,
    staff_sid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    customer_sid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    access_expires_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    KEY ix_ws_ticket_expiry (expires_at),
    CHECK ((staff_sid IS NULL) <> (customer_sid IS NULL)),
    FOREIGN KEY (staff_sid) REFERENCES staff_session (sid),
    FOREIGN KEY (customer_sid) REFERENCES customer_session (sid)
) ENGINE=InnoDB;
