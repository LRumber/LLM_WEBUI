-- Add ordered message persistence so history and model context come from PostgreSQL.
CREATE TABLE conversation_message (
    id                  UUID PRIMARY KEY,
    conversation_id     UUID NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    sequence_no         INTEGER NOT NULL,
    role                VARCHAR(32) NOT NULL CHECK (role IN ('system', 'user', 'assistant')),
    content             TEXT NOT NULL DEFAULT '',
    status              VARCHAR(32) NOT NULL DEFAULT 'completed'
                        CHECK (status IN ('generating', 'completed', 'interrupted', 'failed')),
    input_tokens        INTEGER NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens       INTEGER NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    error_code          VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (conversation_id, sequence_no)
);

-- Primary history lookup follows conversation order; audit lookup follows creation time.
CREATE INDEX idx_conversation_message_conversation
    ON conversation_message (conversation_id, sequence_no);

CREATE INDEX idx_conversation_message_created_at
    ON conversation_message (created_at DESC);
