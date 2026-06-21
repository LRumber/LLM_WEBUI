-- Store model reasoning separately so it can be folded in the UI and excluded from future context.
ALTER TABLE conversation_message
    ADD COLUMN reasoning_content TEXT,
    ADD COLUMN reasoning_duration_ms INTEGER CHECK (reasoning_duration_ms IS NULL OR reasoning_duration_ms >= 0);
