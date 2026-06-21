-- A portable PostgreSQL array keeps local Windows development operational without pgvector.
-- The final offline image upgrades this storage to pgvector for indexed similarity search.
ALTER TABLE document_chunk
    ADD COLUMN embedding REAL[],
    ADD COLUMN embedding_model VARCHAR(128);

-- Existing attached documents enter the durable indexing queue after this feature is deployed.
INSERT INTO processing_task (id, task_type, resource_id)
SELECT gen_random_uuid(), 'knowledge_index', kd.id
FROM knowledge_document kd
WHERE kd.status <> 'superseded'
  AND NOT EXISTS (
      SELECT 1 FROM processing_task pt
      WHERE pt.resource_id = kd.id AND pt.task_type = 'knowledge_index'
  );
