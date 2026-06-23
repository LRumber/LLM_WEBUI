CREATE TABLE system_setting (
    setting_key     VARCHAR(128) PRIMARY KEY,
    setting_value   TEXT,
    description     TEXT NOT NULL DEFAULT '',
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO system_setting (setting_key, setting_value, description)
VALUES
    ('default_embedding_model', '', 'Default embedding-capable model key used by knowledge indexing and search.'),
    ('default_reranker_model', '', 'Default rerank-capable model key reserved for knowledge result reranking.')
ON CONFLICT (setting_key) DO NOTHING;
