package com.lmplatform.knowledge.repository;

import com.lmplatform.knowledge.model.KnowledgeBaseSummary;
import com.lmplatform.knowledge.model.KnowledgeDocumentSummary;
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

    public KnowledgeBaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
}
