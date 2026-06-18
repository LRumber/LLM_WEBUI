-- Track title ownership so delayed AI work cannot overwrite a user-authored title.
ALTER TABLE conversation
    ADD COLUMN title_source VARCHAR(16) NOT NULL DEFAULT 'temporary'
        CHECK (title_source IN ('temporary', 'ai', 'manual')),
    ADD COLUMN title_updated_at TIMESTAMPTZ;

-- Existing prompt-derived titles are temporary and eligible for one AI replacement.
UPDATE conversation
SET title_source = 'temporary', title_updated_at = updated_at;
