-- Knowledge spaces are future-ready for personal, team, and project ownership.
CREATE TABLE knowledge_base (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    description         VARCHAR(500),
    icon                VARCHAR(64),
    space_type          VARCHAR(16) NOT NULL
                        CHECK (space_type IN ('personal', 'team', 'project')),
    owner_user_id       BIGINT NOT NULL REFERENCES app_user (id),
    scope_ref           UUID,
    embedding_config    JSONB NOT NULL DEFAULT '{}'::jsonb,
    rerank_config       JSONB NOT NULL DEFAULT '{}'::jsonb,
    chunking_config     JSONB NOT NULL DEFAULT '{"chunk_tokens":600,"overlap_tokens":100}'::jsonb,
    status              VARCHAR(16) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active', 'disabled', 'deleted')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((space_type = 'personal' AND scope_ref IS NULL)
        OR (space_type IN ('team', 'project') AND scope_ref IS NOT NULL))
);

CREATE TABLE knowledge_member (
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    user_id             BIGINT NOT NULL REFERENCES app_user (id),
    member_role         VARCHAR(16) NOT NULL
                        CHECK (member_role IN ('owner', 'admin', 'editor', 'viewer')),
    permission_source   VARCHAR(16) NOT NULL DEFAULT 'direct'
                        CHECK (permission_source IN ('direct', 'team', 'project', 'inherited')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (knowledge_base_id, user_id)
);

CREATE TABLE knowledge_document (
    id                  UUID PRIMARY KEY,
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    file_id             UUID NOT NULL REFERENCES file_asset (id),
    version             INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    status              VARCHAR(24) NOT NULL DEFAULT 'waiting_parse'
                        CHECK (status IN (
                            'waiting_parse', 'parsing', 'chunking', 'embedding',
                            'ready', 'failed', 'superseded'
                        )),
    chunk_count         INTEGER NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
    summary             TEXT,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message       VARCHAR(1024),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (knowledge_base_id, file_id, version)
);

CREATE TABLE document_chunk (
    id                  UUID PRIMARY KEY,
    document_id         UUID NOT NULL REFERENCES knowledge_document (id) ON DELETE CASCADE,
    chunk_index         INTEGER NOT NULL CHECK (chunk_index >= 0),
    content             TEXT NOT NULL,
    token_count         INTEGER NOT NULL DEFAULT 0 CHECK (token_count >= 0),
    title_path          VARCHAR(1024),
    page_number         INTEGER CHECK (page_number IS NULL OR page_number > 0),
    table_chunk         BOOLEAN NOT NULL DEFAULT FALSE,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- The pgvector embedding column is added with the stage-three embedding pipeline.
    search_vector       TSVECTOR GENERATED ALWAYS AS (
                            to_tsvector('simple', coalesce(title_path, '') || ' ' || content)
                        ) STORED,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_knowledge_base_owner ON knowledge_base (owner_user_id, updated_at DESC)
    WHERE status <> 'deleted';
CREATE INDEX idx_knowledge_member_user ON knowledge_member (user_id, knowledge_base_id);
CREATE INDEX idx_knowledge_document_base ON knowledge_document (knowledge_base_id, updated_at DESC);
CREATE INDEX idx_document_chunk_document ON document_chunk (document_id, chunk_index);
CREATE INDEX idx_document_chunk_search ON document_chunk USING GIN (search_vector);
