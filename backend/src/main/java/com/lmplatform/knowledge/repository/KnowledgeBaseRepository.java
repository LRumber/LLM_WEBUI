package com.lmplatform.knowledge.repository;

import com.lmplatform.knowledge.model.KnowledgeBaseSummary;
import com.lmplatform.knowledge.model.KnowledgeDocumentSummary;
import com.lmplatform.knowledge.model.KnowledgeIndexSource;
import com.lmplatform.knowledge.model.KnowledgeChunk;
import com.lmplatform.knowledge.model.KnowledgeSearchCandidate;
import com.lmplatform.knowledge.model.KnowledgeSearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persists knowledge spaces while enforcing user access in every read and mutation query. */
@Repository
public class KnowledgeBaseRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Creates a personal space and its owner membership as one atomic operation. */
    @Transactional
    public UUID createPersonal(long userId, String name, String description, String icon) {
        UUID knowledgeBaseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_base (
                    id, name, description, icon, space_type, owner_user_id
                ) VALUES (?, ?, ?, ?, 'personal', ?)
                """, knowledgeBaseId, name, description, icon, userId);
        jdbc.update("""
                INSERT INTO knowledge_member (knowledge_base_id, user_id, member_role)
                VALUES (?, ?, 'owner')
                """, knowledgeBaseId, userId);
        return knowledgeBaseId;
    }

    /** Creates a team-owned space and materializes inherited member permissions atomically. */
    @Transactional
    public Optional<UUID> createTeam(
            long userId, UUID teamId, String name, String description, String icon
    ) {
        UUID knowledgeBaseId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO knowledge_base (
                    id, name, description, icon, space_type, owner_user_id, scope_ref
                ) SELECT ?, ?, ?, ?, 'team', ?, ts.id
                    FROM team_space ts
                    JOIN team_member tm ON tm.team_id = ts.id AND tm.user_id = ?
                   WHERE ts.id = ? AND ts.status = 'active' AND tm.member_role IN ('owner', 'admin')
                """, knowledgeBaseId, name, description, icon, userId, userId, teamId);
        if (inserted != 1) return Optional.empty();
        jdbc.update("""
                INSERT INTO knowledge_member (
                    knowledge_base_id, user_id, member_role, permission_source
                ) SELECT ?, tm.user_id,
                         CASE tm.member_role
                           WHEN 'owner' THEN 'owner'
                           WHEN 'admin' THEN 'admin'
                           ELSE 'viewer'
                         END,
                         'team'
                    FROM team_member tm WHERE tm.team_id = ?
                """, knowledgeBaseId, teamId);
        return Optional.of(knowledgeBaseId);
    }

    /** Lists only spaces visible through direct or future inherited membership. */
    public List<KnowledgeBaseSummary> listAccessible(long userId) {
        return jdbc.query("""
                SELECT kb.id, kb.name, kb.description, kb.icon, kb.space_type,
                       km.member_role, COUNT(kd.id) FILTER (WHERE kd.status <> 'superseded') AS document_count,
                       kb.created_at, kb.updated_at
                FROM knowledge_base kb
                JOIN knowledge_member km ON km.knowledge_base_id = kb.id AND km.user_id = ?
                LEFT JOIN knowledge_document kd ON kd.knowledge_base_id = kb.id
                WHERE kb.status = 'active'
                GROUP BY kb.id, km.member_role
                ORDER BY kb.updated_at DESC
                """, (rs, rowNum) -> mapSummary(rs), userId);
    }

    public Optional<KnowledgeBaseSummary> findAccessible(UUID knowledgeBaseId, long userId) {
        return jdbc.query("""
                SELECT kb.id, kb.name, kb.description, kb.icon, kb.space_type,
                       km.member_role, COUNT(kd.id) FILTER (WHERE kd.status <> 'superseded') AS document_count,
                       kb.created_at, kb.updated_at
                FROM knowledge_base kb
                JOIN knowledge_member km ON km.knowledge_base_id = kb.id AND km.user_id = ?
                LEFT JOIN knowledge_document kd ON kd.knowledge_base_id = kb.id
                WHERE kb.id = ? AND kb.status = 'active'
                GROUP BY kb.id, km.member_role
                """, (rs, rowNum) -> mapSummary(rs), userId, knowledgeBaseId).stream().findFirst();
    }

    /** Owner-only mutation keeps the first phase from exposing unfinished team authorization. */
    public boolean updateOwned(
            UUID knowledgeBaseId, long userId, String name, String description, String icon
    ) {
        return jdbc.update("""
                UPDATE knowledge_base SET name = ?, description = ?, icon = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_user_id = ? AND status = 'active'
                """, name, description, icon, knowledgeBaseId, userId) == 1;
    }

    /** Soft deletion preserves auditability while immediately removing the space from retrieval. */
    public boolean softDeleteOwned(UUID knowledgeBaseId, long userId) {
        return jdbc.update("""
                UPDATE knowledge_base SET status = 'deleted', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_user_id = ? AND status = 'active'
                """, knowledgeBaseId, userId) == 1;
    }

    /** Lists documents only through an authorized membership join. */
    public List<KnowledgeDocumentSummary> listDocuments(UUID knowledgeBaseId, long userId) {
        return jdbc.query("""
                SELECT kd.id, kd.file_id, fa.original_name, fa.media_type, fa.size_bytes,
                       fa.status AS file_status, kd.status, kd.chunk_count,
                       COALESCE(kd.error_message, fa.error_message) AS error_message, kd.created_at
                FROM knowledge_document kd
                JOIN knowledge_base kb ON kb.id = kd.knowledge_base_id AND kb.status = 'active'
                JOIN knowledge_member km ON km.knowledge_base_id = kb.id AND km.user_id = ?
                JOIN file_asset fa ON fa.id = kd.file_id
                WHERE kd.knowledge_base_id = ? AND kd.status <> 'superseded'
                ORDER BY kd.created_at DESC
                """, (rs, rowNum) -> mapDocument(rs), userId, knowledgeBaseId);
    }

    /** Adds only a file owned by the caller to a space where they can edit documents. */
    @Transactional
    public Optional<KnowledgeDocumentSummary> attachDocument(
            UUID knowledgeBaseId, UUID fileId, long userId
    ) {
        List<KnowledgeDocumentSummary> existing = listDocuments(knowledgeBaseId, userId).stream()
                .filter(document -> document.fileId().equals(fileId)).toList();
        if (!existing.isEmpty()) return Optional.of(existing.getFirst());

        UUID documentId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO knowledge_document (id, knowledge_base_id, file_id, version)
                SELECT ?, kb.id, fa.id, 1
                FROM knowledge_base kb
                JOIN knowledge_member km ON km.knowledge_base_id = kb.id AND km.user_id = ?
                JOIN file_asset fa ON fa.id = ? AND fa.owner_user_id = ?
                WHERE kb.id = ? AND kb.status = 'active'
                  AND km.member_role IN ('owner', 'admin', 'editor')
                  AND fa.asset_kind = 'document'
                """, documentId, userId, fileId, userId, knowledgeBaseId);
        if (inserted != 1) return Optional.empty();
        jdbc.update("""
                INSERT INTO processing_task (id, task_type, resource_id)
                VALUES (?, 'knowledge_index', ?)
                """, UUID.randomUUID(), documentId);
        return listDocuments(knowledgeBaseId, userId).stream()
                .filter(document -> document.id().equals(documentId)).findFirst();
    }

    /** Removes a document from retrieval without deleting the owner's original MinIO file. */
    public boolean removeDocument(UUID knowledgeBaseId, UUID documentId, long userId) {
        return jdbc.update("""
                UPDATE knowledge_document kd SET status = 'superseded', updated_at = CURRENT_TIMESTAMP
                FROM knowledge_member km, knowledge_base kb
                WHERE kd.id = ? AND kd.knowledge_base_id = ?
                  AND km.knowledge_base_id = kd.knowledge_base_id AND km.user_id = ?
                  AND km.member_role IN ('owner', 'admin', 'editor')
                  AND kb.id = kd.knowledge_base_id AND kb.status = 'active'
                  AND kd.status <> 'superseded'
                """, documentId, knowledgeBaseId, userId) == 1;
    }

    /** Returns parsed text only after the file parser has completed successfully. */
    public Optional<KnowledgeIndexSource> findIndexSource(UUID documentId) {
        return jdbc.query("""
                SELECT kd.id, kd.file_id, fa.extracted_text
                FROM knowledge_document kd
                JOIN knowledge_base kb ON kb.id = kd.knowledge_base_id AND kb.status = 'active'
                JOIN file_asset fa ON fa.id = kd.file_id AND fa.status = 'ready'
                WHERE kd.id = ? AND kd.status NOT IN ('ready', 'superseded')
                  AND fa.extracted_text IS NOT NULL
                """, (rs, rowNum) -> new KnowledgeIndexSource(
                rs.getObject("id", UUID.class), rs.getObject("file_id", UUID.class),
                rs.getString("extracted_text")
        ), documentId).stream().findFirst();
    }

    public List<UUID> findActiveDocumentIds(UUID fileId) {
        return jdbc.queryForList("""
                SELECT id FROM knowledge_document
                WHERE file_id = ? AND status NOT IN ('ready', 'superseded')
                """, UUID.class, fileId);
    }

    public void markIndexing(UUID documentId) {
        jdbc.update("""
                UPDATE knowledge_document SET status = 'chunking', error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, documentId);
        jdbc.update("""
                UPDATE processing_task SET status = 'running', attempts = attempts + 1,
                    started_at = CURRENT_TIMESTAMP, error_message = NULL
                WHERE resource_id = ? AND task_type = 'knowledge_index' AND status IN ('queued', 'failed')
                """, documentId);
    }

    /** Replaces all chunks atomically so readers never observe a partially indexed document. */
    @Transactional
    public void replaceChunks(
            UUID documentId, List<KnowledgeChunk> chunks, List<float[]> vectors, String model
    ) {
        if (!vectors.isEmpty() && chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("Chunk/vector count mismatch");
        }
        jdbc.update("DELETE FROM document_chunk WHERE document_id = ?", documentId);
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunk chunk = chunks.get(index);
            Float[] boxed = vectors.isEmpty() ? null : new Float[vectors.get(index).length];
            for (int vectorIndex = 0; boxed != null && vectorIndex < boxed.length; vectorIndex++) {
                boxed[vectorIndex] = vectors.get(index)[vectorIndex];
            }
            jdbc.update("""
                    INSERT INTO document_chunk (
                        id, document_id, chunk_index, content, token_count,
                        embedding, embedding_model
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, statement -> {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, documentId);
                statement.setInt(3, chunk.index());
                statement.setString(4, chunk.content());
                statement.setInt(5, chunk.tokenCount());
                if (boxed == null) {
                    statement.setNull(6, java.sql.Types.ARRAY);
                } else {
                    statement.setArray(6, statement.getConnection().createArrayOf("real", boxed));
                }
                statement.setString(7, model);
            });
        }
        jdbc.update("""
                UPDATE knowledge_document SET status = 'ready', chunk_count = ?, error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, chunks.size(), documentId);
        jdbc.update("""
                UPDATE processing_task SET status = 'completed', finished_at = CURRENT_TIMESTAMP
                WHERE resource_id = ? AND task_type = 'knowledge_index'
                """, documentId);
    }

    public void failIndexing(UUID documentId, String message) {
        jdbc.update("""
                UPDATE knowledge_document SET status = 'failed', error_message = ?,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, message, documentId);
        jdbc.update("""
                UPDATE processing_task SET status = 'failed', error_message = ?,
                    finished_at = CURRENT_TIMESTAMP
                WHERE resource_id = ? AND task_type = 'knowledge_index'
                """, message, documentId);
    }

    /** Retrieves lexical candidates under the same membership boundary used by document listing. */
    public List<KnowledgeSearchCandidate> searchKeywordCandidates(
            UUID knowledgeBaseId, long userId, String query, int limit
    ) {
        return jdbc.query("""
                SELECT dc.id, dc.document_id, kd.file_id, fa.original_name, dc.content,
                       dc.page_number, dc.title_path, dc.embedding,
                       GREATEST(
                           ts_rank(dc.search_vector, plainto_tsquery('simple', ?)),
                           CASE WHEN dc.content ILIKE ('%' || ? || '%') THEN 1.0 ELSE 0.0 END
                       ) AS keyword_score
                FROM document_chunk dc
                JOIN knowledge_document kd ON kd.id = dc.document_id AND kd.status = 'ready'
                JOIN knowledge_member km ON km.knowledge_base_id = kd.knowledge_base_id
                                        AND km.user_id = ?
                JOIN knowledge_base kb ON kb.id = kd.knowledge_base_id AND kb.status = 'active'
                JOIN file_asset fa ON fa.id = kd.file_id
                WHERE kd.knowledge_base_id = ?
                  AND (dc.search_vector @@ plainto_tsquery('simple', ?)
                       OR dc.content ILIKE ('%' || ? || '%'))
                ORDER BY keyword_score DESC, dc.chunk_index
                LIMIT ?
                """, (rs, rowNum) -> mapSearchCandidate(rs),
                query, query, userId, knowledgeBaseId, query, query, limit);
    }

    /** Loads bounded vector candidates; pgvector replaces this scan in the final optimized image. */
    public List<KnowledgeSearchCandidate> listVectorCandidates(
            UUID knowledgeBaseId, long userId, int limit
    ) {
        return jdbc.query("""
                SELECT dc.id, dc.document_id, kd.file_id, fa.original_name, dc.content,
                       dc.page_number, dc.title_path, dc.embedding, 0.0 AS keyword_score
                FROM document_chunk dc
                JOIN knowledge_document kd ON kd.id = dc.document_id AND kd.status = 'ready'
                JOIN knowledge_member km ON km.knowledge_base_id = kd.knowledge_base_id
                                        AND km.user_id = ?
                JOIN knowledge_base kb ON kb.id = kd.knowledge_base_id AND kb.status = 'active'
                JOIN file_asset fa ON fa.id = kd.file_id
                WHERE kd.knowledge_base_id = ? AND dc.embedding IS NOT NULL
                ORDER BY kd.updated_at DESC, dc.chunk_index
                LIMIT ?
                """, (rs, rowNum) -> mapSearchCandidate(rs), userId, knowledgeBaseId, limit);
    }

    /** Supplies bounded ready chunks when lexical search has no hit and vector search is unavailable. */
    public List<KnowledgeSearchCandidate> listFallbackCandidates(
            UUID knowledgeBaseId, long userId, int limit
    ) {
        return jdbc.query("""
                SELECT dc.id, dc.document_id, kd.file_id, fa.original_name, dc.content,
                       dc.page_number, dc.title_path, dc.embedding, 0.15 AS keyword_score
                FROM document_chunk dc
                JOIN knowledge_document kd ON kd.id = dc.document_id AND kd.status = 'ready'
                JOIN knowledge_member km ON km.knowledge_base_id = kd.knowledge_base_id
                                        AND km.user_id = ?
                JOIN knowledge_base kb ON kb.id = kd.knowledge_base_id AND kb.status = 'active'
                JOIN file_asset fa ON fa.id = kd.file_id
                WHERE kd.knowledge_base_id = ?
                ORDER BY kd.updated_at DESC, dc.chunk_index
                LIMIT ?
                """, (rs, rowNum) -> mapSearchCandidate(rs), userId, knowledgeBaseId, limit);
    }

    public void recordRetrieval(
            UUID knowledgeBaseId, long userId, String query, String mode,
            int candidateCount, int durationMs, List<KnowledgeSearchResult> results
    ) {
        try {
            jdbc.update("""
                    INSERT INTO retrieval_log (
                        id, knowledge_base_id, user_id, query_text, retrieval_mode,
                        candidate_count, result_count, duration_ms, result_snapshot
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, UUID.randomUUID(), knowledgeBaseId, userId, query, mode,
                    candidateCount, results.size(), durationMs, objectMapper.writeValueAsString(results));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize retrieval diagnostics", exception);
        }
    }

    private KnowledgeBaseSummary mapSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new KnowledgeBaseSummary(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("icon"),
                rs.getString("space_type"),
                rs.getString("member_role"),
                rs.getInt("document_count"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private KnowledgeDocumentSummary mapDocument(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new KnowledgeDocumentSummary(
                rs.getObject("id", UUID.class), rs.getObject("file_id", UUID.class),
                rs.getString("original_name"), rs.getString("media_type"),
                rs.getLong("size_bytes"), rs.getString("file_status"),
                rs.getString("status"), rs.getInt("chunk_count"),
                rs.getString("error_message"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private KnowledgeSearchCandidate mapSearchCandidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Array sqlArray = rs.getArray("embedding");
        float[] embedding = null;
        if (sqlArray != null) {
            Object[] values = (Object[]) sqlArray.getArray();
            embedding = new float[values.length];
            for (int index = 0; index < values.length; index++) {
                embedding[index] = ((Number) values[index]).floatValue();
            }
        }
        return new KnowledgeSearchCandidate(
                rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("file_id", UUID.class), rs.getString("original_name"),
                rs.getString("content"), rs.getObject("page_number", Integer.class),
                rs.getString("title_path"), rs.getDouble("keyword_score"), embedding
        );
    }
}
