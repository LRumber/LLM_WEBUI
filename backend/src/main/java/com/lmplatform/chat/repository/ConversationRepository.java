package com.lmplatform.chat.repository;

import com.lmplatform.chat.model.ConversationMessage;
import com.lmplatform.chat.model.ConversationSummary;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long ensureDevelopmentUser() {
        Long id = jdbc.queryForObject("""
                INSERT INTO app_user (external_user_id, account_number, display_name, user_number)
                VALUES (-1, 'local-dev', '开发用户', 'local-dev')
                ON CONFLICT (external_user_id) DO UPDATE SET updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """, Long.class);
        return id;
    }

    public UUID createConversation(long userId, String model, String firstPrompt) {
        UUID id = UUID.randomUUID();
        String title = firstPrompt.strip().replaceAll("\\s+", " ");
        if (title.length() > 60) {
            title = title.substring(0, 60);
        }
        jdbc.update("""
                INSERT INTO conversation (
                    id, user_id, model_key, title, title_source, title_updated_at
                ) VALUES (?, ?, ?, ?, 'temporary', CURRENT_TIMESTAMP)
                """, id, userId, model, title);
        return id;
    }

    public boolean belongsToUser(UUID conversationId, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
                Integer.class,
                conversationId,
                userId
        );
        return count != null && count > 0;
    }

    @Transactional
    public UUID appendMessage(UUID conversationId, String role, String content, String status) {
        Integer sequence = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM conversation_message WHERE conversation_id = ?",
                Integer.class,
                conversationId
        );
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO conversation_message (id, conversation_id, sequence_no, role, content, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, conversationId, sequence, role, content, status);
        jdbc.update("UPDATE conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", conversationId);
        return id;
    }

    public List<Map<String, String>> modelMessages(UUID conversationId) {
        return jdbc.query("""
                SELECT role, content
                FROM conversation_message
                WHERE conversation_id = ?
                  AND status = 'completed'
                  AND content <> ''
                ORDER BY sequence_no
                """, (rs, rowNum) -> Map.of(
                "role", rs.getString("role"),
                "content", rs.getString("content")
        ), conversationId);
    }

    public void finishAssistantMessage(
            UUID messageId,
            String content,
            String status,
            int inputTokens,
            int outputTokens,
            String errorCode
    ) {
        jdbc.update("""
                UPDATE conversation_message
                SET content = ?, status = ?, input_tokens = ?, output_tokens = ?, error_code = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, content, status, inputTokens, outputTokens, errorCode, messageId);
        jdbc.update("""
                UPDATE conversation
                SET message_count = (
                        SELECT COUNT(*) FROM conversation_message
                        WHERE conversation_id = (SELECT conversation_id FROM conversation_message WHERE id = ?)
                    ),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = (SELECT conversation_id FROM conversation_message WHERE id = ?)
                """, messageId, messageId);
    }

    public boolean updateGeneratedTitle(UUID conversationId, String title) {
        int updated = jdbc.update("""
                UPDATE conversation
                SET title = ?, title_source = 'ai', title_updated_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND title_source = 'temporary'
                """, title, conversationId);
        return updated > 0;
    }

    public boolean renameConversation(UUID conversationId, long userId, String title) {
        int updated = jdbc.update("""
                UPDATE conversation
                SET title = ?, title_source = 'manual', title_updated_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND deleted_at IS NULL
                """, title.strip(), conversationId, userId);
        return updated > 0;
    }

    @Transactional
    public void recordTitleUsage(
            UUID requestId,
            long userId,
            UUID conversationId,
            String model,
            int inputTokens,
            int outputTokens,
            int durationMs
    ) {
        jdbc.update("""
                INSERT INTO usage_event (
                    request_id, user_id, conversation_id, model_key, application_key,
                    input_tokens, output_tokens, duration_ms, status
                ) VALUES (?, ?, ?, ?, 'system:title', ?, ?, ?, 'completed')
                """, requestId, userId, conversationId, model, inputTokens, outputTokens, durationMs);
        jdbc.update("""
                INSERT INTO daily_user_usage (
                    usage_date, user_id, conversation_count, request_count, input_tokens, output_tokens
                ) VALUES (CURRENT_DATE, ?, 0, 1, ?, ?)
                ON CONFLICT (usage_date, user_id) DO UPDATE SET
                    request_count = daily_user_usage.request_count + 1,
                    input_tokens = daily_user_usage.input_tokens + EXCLUDED.input_tokens,
                    output_tokens = daily_user_usage.output_tokens + EXCLUDED.output_tokens,
                    last_aggregated_at = CURRENT_TIMESTAMP
                """, userId, inputTokens, outputTokens);
    }

    @Transactional
    public void recordCompletedUsage(
            UUID requestId,
            long userId,
            UUID conversationId,
            String model,
            int inputTokens,
            int outputTokens,
            Integer firstTokenMs,
            int durationMs,
            boolean newConversation
    ) {
        jdbc.update("""
                INSERT INTO usage_event (
                    request_id, user_id, conversation_id, model_key, input_tokens, output_tokens,
                    first_token_ms, duration_ms, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'completed')
                """, requestId, userId, conversationId, model, inputTokens, outputTokens,
                firstTokenMs, durationMs);
        jdbc.update("""
                UPDATE conversation
                SET message_count = (SELECT COUNT(*) FROM conversation_message WHERE conversation_id = ?),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, conversationId, conversationId);
        jdbc.update("""
                INSERT INTO daily_user_usage (
                    usage_date, user_id, conversation_count, request_count, input_tokens, output_tokens
                ) VALUES (CURRENT_DATE, ?, ?, 1, ?, ?)
                ON CONFLICT (usage_date, user_id) DO UPDATE SET
                    conversation_count = daily_user_usage.conversation_count + EXCLUDED.conversation_count,
                    request_count = daily_user_usage.request_count + 1,
                    input_tokens = daily_user_usage.input_tokens + EXCLUDED.input_tokens,
                    output_tokens = daily_user_usage.output_tokens + EXCLUDED.output_tokens,
                    last_aggregated_at = CURRENT_TIMESTAMP
                """, userId, newConversation ? 1 : 0, inputTokens, outputTokens);
    }

    public List<ConversationSummary> listConversations(long userId) {
        return jdbc.query("""
                SELECT c.id, c.title, c.model_key, c.message_count, c.created_at, c.updated_at,
                       COALESCE((
                           SELECT cm.content FROM conversation_message cm
                           WHERE cm.conversation_id = c.id AND cm.content <> ''
                           ORDER BY cm.sequence_no DESC LIMIT 1
                       ), '') AS preview
                FROM conversation c
                WHERE c.user_id = ? AND c.deleted_at IS NULL
                ORDER BY c.updated_at DESC
                LIMIT 100
                """, (rs, rowNum) -> new ConversationSummary(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("model_key"),
                rs.getInt("message_count"),
                rs.getString("preview"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        ), userId);
    }

    public List<ConversationMessage> listMessages(UUID conversationId, long userId) {
        return jdbc.query("""
                SELECT cm.id, cm.role, cm.content, cm.status, cm.input_tokens, cm.output_tokens, cm.created_at
                FROM conversation_message cm
                JOIN conversation c ON c.id = cm.conversation_id
                WHERE cm.conversation_id = ? AND c.user_id = ? AND c.deleted_at IS NULL
                ORDER BY cm.sequence_no
                """, (rs, rowNum) -> new ConversationMessage(
                rs.getObject("id", UUID.class),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getInt("input_tokens"),
                rs.getInt("output_tokens"),
                rs.getObject("created_at", OffsetDateTime.class)
        ), conversationId, userId);
    }
}
