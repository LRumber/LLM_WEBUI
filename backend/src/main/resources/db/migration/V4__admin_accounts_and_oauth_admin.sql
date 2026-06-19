-- Keep local administration credentials separate from OAuth2 shadow users.
CREATE TABLE admin_account (
    id                  BIGSERIAL PRIMARY KEY,
    username            VARCHAR(64) NOT NULL UNIQUE,
    password_hash       VARCHAR(100) NOT NULL,
    display_name        VARCHAR(128) NOT NULL,
    status              SMALLINT NOT NULL DEFAULT 1,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- OAuth2 users remain externally owned but may be granted local administration rights.
ALTER TABLE app_user
    ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_app_user_admin ON app_user (is_admin) WHERE is_admin = TRUE;
