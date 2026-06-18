ALTER TABLE conversation
    ADD COLUMN title_source VARCHAR(16) NOT NULL DEFAULT 'temporary'
        CHECK (title_source IN ('temporary', 'ai', 'manual')),
    ADD COLUMN title_updated_at TIMESTAMPTZ;

UPDATE conversation
SET title_source = 'temporary', title_updated_at = updated_at;
