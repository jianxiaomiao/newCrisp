ALTER TABLE customer
    ADD COLUMN display_name VARCHAR(80) NULL,
    ADD COLUMN email VARCHAR(254) NULL,
    ADD COLUMN phone VARCHAR(32) NULL,
    ADD COLUMN avatar_url VARCHAR(512) NULL,
    ADD COLUMN attributes JSON NULL,
    ADD COLUMN last_page_url VARCHAR(512) NULL,
    ADD COLUMN last_user_agent VARCHAR(255) NULL,
    ADD COLUMN profile_updated_at DATETIME(3) NULL;

ALTER TABLE launch_grant
    ADD COLUMN customer_name VARCHAR(80) NULL,
    ADD COLUMN customer_email VARCHAR(254) NULL,
    ADD COLUMN customer_phone VARCHAR(32) NULL,
    ADD COLUMN customer_avatar VARCHAR(512) NULL,
    ADD COLUMN customer_attributes JSON NULL;
