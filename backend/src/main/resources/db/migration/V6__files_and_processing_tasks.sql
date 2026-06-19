-- Persist object metadata separately from asynchronous parsing state and message references.
CREATE TABLE file_asset (
    id                  UUID PRIMARY KEY,
    owner_user_id       BIGINT NOT NULL REFERENCES app_user (id),
    object_key          VARCHAR(512) NOT NULL UNIQUE,
    original_name       VARCHAR(512) NOT NULL,
    media_type          VARCHAR(255) NOT NULL,
    size_bytes          BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256              VARCHAR(64) NOT NULL,
    asset_kind          VARCHAR(16) NOT NULL
                        CHECK (asset_kind IN ('document', 'image', 'audio', 'other')),
    status              VARCHAR(16) NOT NULL DEFAULT 'uploaded'
                        CHECK (status IN ('uploaded', 'processing', 'ready', 'failed')),
    extracted_text      TEXT,
    parse_metadata      JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message       VARCHAR(1024),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE processing_task (
    id                  UUID PRIMARY KEY,
    task_type           VARCHAR(32) NOT NULL,
    resource_id         UUID NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'queued'
                        CHECK (status IN ('queued', 'running', 'completed', 'failed', 'cancelled')),
    attempts            INTEGER NOT NULL DEFAULT 0,
    available_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    error_message       VARCHAR(1024),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE message_attachment (
    message_id          UUID NOT NULL REFERENCES conversation_message (id) ON DELETE CASCADE,
    file_id             UUID NOT NULL REFERENCES file_asset (id),
    position            INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (message_id, file_id)
);

CREATE INDEX idx_file_asset_owner_time ON file_asset (owner_user_id, created_at DESC);
CREATE INDEX idx_processing_task_claim ON processing_task (status, available_at);
