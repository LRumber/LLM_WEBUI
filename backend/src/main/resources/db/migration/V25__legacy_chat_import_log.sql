CREATE TABLE legacy_chat_import (
    user_id             BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    legacy_user_key     VARCHAR(128) NOT NULL,
    source_path         VARCHAR(1024) NOT NULL,
    status              VARCHAR(16) NOT NULL
                        CHECK (status IN ('success', 'failed')),
    conversation_count  INTEGER NOT NULL DEFAULT 0 CHECK (conversation_count >= 0),
    message_count       INTEGER NOT NULL DEFAULT 0 CHECK (message_count >= 0),
    error_message       VARCHAR(1024),
    imported_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_legacy_chat_import_key ON legacy_chat_import (legacy_user_key);
