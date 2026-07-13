ALTER TABLE conversation_message
    ADD COLUMN version_group_id UUID,
    ADD COLUMN version_index INTEGER CHECK (version_index IS NULL OR version_index > 0),
    ADD COLUMN active_version BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_conversation_message_version_group
    ON conversation_message (conversation_id, version_group_id, version_index)
    WHERE version_group_id IS NOT NULL;
