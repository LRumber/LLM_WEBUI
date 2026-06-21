package com.lmplatform.knowledge.repository;

import com.lmplatform.knowledge.model.KnowledgeBaseSummary;
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
}
