-- Store compacted long-running chat context without changing visible message history.
ALTER TABLE conversation
    ADD COLUMN context_summary TEXT,
    ADD COLUMN context_summary_until_sequence INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN context_summary_updated_at TIMESTAMPTZ;
