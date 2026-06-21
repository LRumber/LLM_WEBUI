-- Retrieval diagnostics support tuning and later RAG quality evaluation without external telemetry.
CREATE TABLE retrieval_log (
    id                  UUID PRIMARY KEY,
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    user_id             BIGINT NOT NULL REFERENCES app_user (id),
    query_text          TEXT NOT NULL,
    retrieval_mode      VARCHAR(16) NOT NULL
                        CHECK (retrieval_mode IN ('keyword', 'hybrid', 'reranked')),
    candidate_count     INTEGER NOT NULL DEFAULT 0,
    result_count        INTEGER NOT NULL DEFAULT 0,
    duration_ms         INTEGER NOT NULL CHECK (duration_ms >= 0),
    result_snapshot     JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_retrieval_log_base_time
    ON retrieval_log (knowledge_base_id, created_at DESC);
