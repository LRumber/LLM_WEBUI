-- Conversation bindings are immutable snapshots selected before the first user message.
CREATE TABLE conversation_knowledge_base (
    conversation_id     UUID NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_base (id),
    knowledge_base_name VARCHAR(100) NOT NULL,
    position            INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, knowledge_base_id)
);

CREATE TABLE message_citation (
    message_id          UUID NOT NULL REFERENCES conversation_message (id) ON DELETE CASCADE,
    citation_no         INTEGER NOT NULL CHECK (citation_no > 0),
    chunk_id            UUID NOT NULL REFERENCES document_chunk (id),
    document_id         UUID NOT NULL REFERENCES knowledge_document (id),
    file_id             UUID NOT NULL REFERENCES file_asset (id),
    filename            VARCHAR(512) NOT NULL,
    page_number         INTEGER,
    title_path          VARCHAR(1024),
    content_snapshot    TEXT NOT NULL,
    relevance_score     DOUBLE PRECISION NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id, citation_no)
);

CREATE INDEX idx_conversation_knowledge_base
    ON conversation_knowledge_base (knowledge_base_id, conversation_id);
