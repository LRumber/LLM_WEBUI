ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS id_no VARCHAR(128),
    ADD COLUMN IF NOT EXISTS id_type SMALLINT,
    ADD COLUMN IF NOT EXISTS username VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_app_user_id_no ON app_user (id_no);
CREATE INDEX IF NOT EXISTS idx_app_user_username ON app_user (username);
