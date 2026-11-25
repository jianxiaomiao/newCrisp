ALTER TABLE department ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE staff_account ADD COLUMN access_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE websocket_ticket ADD COLUMN staff_access_version BIGINT;
