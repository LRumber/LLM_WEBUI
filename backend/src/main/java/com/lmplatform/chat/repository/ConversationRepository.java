package com.lmplatform.chat.repository;

import com.lmplatform.chat.model.ConversationMessage;
import com.lmplatform.chat.model.ConversationAttachment;
import com.lmplatform.chat.model.ConversationSummary;
import com.lmplatform.chat.model.PromptTemplate;
import com.lmplatform.chat.model.ModelContextMessage;
import com.lmplatform.chat.model.ModelMessageAttachment;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Owns SQL persistence for conversations, messages, titles, and usage aggregates. */
@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the temporary local user used before OAuth2 principal integration is complete.
     * Replace this boundary with the authenticated shadow-user id when login is connected.
     */
    public long ensureDevelopmentUser() {
        Long id = jdbc.queryForObject("""
                INSERT INTO app_user (external_user_id, account_number, display_name, user_number)
                VALUES (-1, 'local-dev', '开发用户', 'local-dev')
                ON CONFLICT (external_user_id) DO UPDATE SET updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """, Long.class);
        return id;
    }

    /** Creates a conversation with an immediate first-prompt title marked as temporary. */
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

    /** Checks ownership without exposing soft-deleted conversations. */
    public boolean belongsToUser(UUID conversationId, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
                Integer.class,
                conversationId,
                userId
        );
        return count != null && count > 0;
    }

    /** Appends a message with the next stable sequence number and touches the parent conversation. */
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

    /** Loads only completed, non-empty messages that are safe to send back to a model. */
    public List<Map<String, String>> modelMessages(UUID conversationId, int limit) {
        return jdbc.query("""
                SELECT role, content FROM (
                    SELECT cm.role,
                           cm.content || COALESCE((
                               SELECT string_agg(E'\n\n[附件: ' || fa.original_name || E']\n' ||
                                                 COALESCE(fa.extracted_text, ''), '' ORDER BY ma.position)
                               FROM message_attachment ma JOIN file_asset fa ON fa.id = ma.file_id
                               WHERE ma.message_id = cm.id AND fa.status = 'ready'
                           ), '') AS content,
                           cm.sequence_no
                    FROM conversation_message cm
                    WHERE cm.conversation_id = ? AND cm.status = 'completed'
                      AND cm.superseded_at IS NULL AND cm.content <> ''
                    ORDER BY cm.sequence_no DESC LIMIT ?
                ) recent_messages ORDER BY sequence_no
                """, (rs, rowNum) -> Map.of(
                "role", rs.getString("role"),
                "content", rs.getString("content")
        ), conversationId, limit);
    }

    /** Loads bounded active context with private image object references for multimodal requests. */
    public List<ModelContextMessage> modelContextMessages(UUID conversationId, int limit) {
        return jdbc.query("""
                SELECT id, role, content FROM (
                    SELECT cm.id, cm.role,
                           cm.content || COALESCE((
                               SELECT string_agg(E'\n\n[附件: ' || fa.original_name || E']\n' ||
                                                 COALESCE(fa.extracted_text, ''), '' ORDER BY ma.position)
                               FROM message_attachment ma JOIN file_asset fa ON fa.id = ma.file_id
                               WHERE ma.message_id = cm.id AND fa.status = 'ready'
                                 AND fa.asset_kind <> 'image'
                           ), '') AS content,
                           cm.sequence_no
                    FROM conversation_message cm
                    WHERE cm.conversation_id = ? AND cm.status = 'completed'
                      AND cm.superseded_at IS NULL AND cm.content <> ''
                    ORDER BY cm.sequence_no DESC LIMIT ?
                ) recent_messages ORDER BY sequence_no
                """, (rs, rowNum) -> {
            UUID messageId = rs.getObject("id", UUID.class);
            List<ModelMessageAttachment> attachments = jdbc.query("""
                    SELECT fa.object_key, fa.media_type, fa.asset_kind
                    FROM message_attachment ma JOIN file_asset fa ON fa.id = ma.file_id
                    WHERE ma.message_id = ? AND fa.status = 'ready' AND fa.asset_kind = 'image'
                    ORDER BY ma.position
                    """, (attachmentRs, attachmentRow) -> new ModelMessageAttachment(
                    attachmentRs.getString("object_key"), attachmentRs.getString("media_type"),
                    attachmentRs.getString("asset_kind")
            ), messageId);
            return new ModelContextMessage(
                    rs.getString("role"), rs.getString("content"), attachments
            );
        }, conversationId, limit);
    }

    /** Preserves the legacy unbounded call for title generation and focused unit tests. */
    public List<Map<String, String>> modelMessages(UUID conversationId) {
        return modelMessages(conversationId, 100);
    }

    /** Links only ready or uploaded files owned by the same user to one newly persisted message. */
    public void attachFiles(UUID messageId, List<UUID> fileIds, long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        for (int index = 0; index < fileIds.size(); index++) {
            jdbc.update("""
                    INSERT INTO message_attachment (message_id, file_id, position)
                    SELECT ?, id, ? FROM file_asset WHERE id = ? AND owner_user_id = ?
                    ON CONFLICT DO NOTHING
                    """, messageId, index, fileIds.get(index), userId);
        }
    }

    /** Edits an owned user message and supersedes later messages instead of deleting audit history. */
    @Transactional
    public boolean editUserMessage(UUID conversationId, UUID messageId, long userId, String content) {
        Integer sequence = jdbc.query("""
                SELECT cm.sequence_no FROM conversation_message cm
                JOIN conversation c ON c.id = cm.conversation_id
                WHERE cm.id = ? AND cm.conversation_id = ? AND cm.role = 'user'
                  AND cm.superseded_at IS NULL AND c.user_id = ? AND c.deleted_at IS NULL
                """, (rs, rowNum) -> rs.getInt("sequence_no"), messageId, conversationId, userId)
                .stream().findFirst().orElse(null);
        if (sequence == null) {
            return false;
        }
        jdbc.update("""
                UPDATE conversation_message SET content = ?, edited_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, content.strip(), messageId);
        jdbc.update("""
                UPDATE conversation_message SET superseded_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE conversation_id = ? AND sequence_no > ? AND superseded_at IS NULL
                """, conversationId, sequence);
        jdbc.update("UPDATE conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", conversationId);
        return true;
    }

    /** Copies active history through one message into a separately addressable conversation branch. */
    @Transactional
    public UUID branchConversation(UUID conversationId, UUID messageId, long userId) {
        Integer sequence = jdbc.query("""
                SELECT cm.sequence_no FROM conversation_message cm
                JOIN conversation c ON c.id = cm.conversation_id
                WHERE cm.id = ? AND cm.conversation_id = ? AND cm.superseded_at IS NULL
                  AND c.user_id = ? AND c.deleted_at IS NULL
                """, (rs, rowNum) -> rs.getInt("sequence_no"), messageId, conversationId, userId)
                .stream().findFirst().orElse(null);
        if (sequence == null) {
            return null;
        }
        UUID branchId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO conversation (
                    id, user_id, team_id, model_key, title, title_source, title_updated_at,
                    parent_conversation_id, branch_from_message_id
                ) SELECT ?, user_id, team_id, model_key, title || '（分支）', 'manual',
                         CURRENT_TIMESTAMP, id, ? FROM conversation WHERE id = ?
                """, branchId, messageId, conversationId);
        jdbc.update("""
                INSERT INTO conversation_message (
                    id, conversation_id, sequence_no, role, content, status,
                    input_tokens, output_tokens, error_code, created_at, updated_at
                ) SELECT gen_random_uuid(), ?, sequence_no, role, content, status,
                         input_tokens, output_tokens, error_code, created_at, updated_at
                  FROM conversation_message
                 WHERE conversation_id = ? AND sequence_no <= ? AND superseded_at IS NULL
                 ORDER BY sequence_no
                """, branchId, conversationId, sequence);
        jdbc.update("""
                UPDATE conversation SET message_count = (
                    SELECT COUNT(*) FROM conversation_message WHERE conversation_id = ?
                ) WHERE id = ?
                """, branchId, branchId);
        return branchId;
    }

    /** Supersedes one assistant answer and every later message before a replacement is generated. */
    @Transactional
    public boolean prepareAssistantRegeneration(UUID conversationId, UUID messageId, long userId) {
        Integer sequence = jdbc.query("""
                SELECT cm.sequence_no FROM conversation_message cm
                JOIN conversation c ON c.id = cm.conversation_id
                WHERE cm.id = ? AND cm.conversation_id = ? AND cm.role = 'assistant'
                  AND cm.superseded_at IS NULL AND c.user_id = ? AND c.deleted_at IS NULL
                """, (rs, rowNum) -> rs.getInt("sequence_no"), messageId, conversationId, userId)
                .stream().findFirst().orElse(null);
        if (sequence == null) {
            return false;
        }
        jdbc.update("""
                UPDATE conversation_message SET superseded_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE conversation_id = ? AND sequence_no >= ? AND superseded_at IS NULL
                """, conversationId, sequence);
        jdbc.update("UPDATE conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", conversationId);
        return true;
    }

    /** Lists enabled system templates and personal templates owned by the current user. */
    public List<PromptTemplate> listPromptTemplates(long userId) {
        return jdbc.query("""
                SELECT id, name, description, system_prompt, scope, enabled FROM prompt_template
                WHERE enabled = TRUE AND (scope = 'system' OR owner_user_id = ?)
                ORDER BY scope, name
                """, (rs, rowNum) -> new PromptTemplate(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("description"), rs.getString("system_prompt"), rs.getString("scope"),
                rs.getBoolean("enabled")
        ), userId);
    }

    /** Lists all system templates for administrators, including disabled records. */
    public List<PromptTemplate> listSystemPromptTemplates() {
        return jdbc.query("""
                SELECT id, name, description, system_prompt, scope, enabled FROM prompt_template
                WHERE scope = 'system' ORDER BY name
                """, (rs, rowNum) -> new PromptTemplate(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("description"), rs.getString("system_prompt"), rs.getString("scope"),
                rs.getBoolean("enabled")
        ));
    }

    /** Creates either an owner-bound personal template or a platform-owned system template. */
    public PromptTemplate createPromptTemplate(
            Long ownerUserId,
            String scope,
            String name,
            String description,
            String systemPrompt,
            boolean enabled
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prompt_template (
                    id, name, description, system_prompt, owner_user_id, scope, enabled
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, name.strip(), description, systemPrompt.strip(), ownerUserId, scope, enabled);
        return new PromptTemplate(id, name.strip(), description, systemPrompt.strip(), scope, enabled);
    }

    /** Updates only a personal template owned by the requesting user. */
    public boolean updatePersonalPromptTemplate(
            UUID id, long userId, String name, String description, String systemPrompt, boolean enabled
    ) {
        return jdbc.update("""
                UPDATE prompt_template SET name = ?, description = ?, system_prompt = ?, enabled = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_user_id = ? AND scope = 'personal'
                """, name.strip(), description, systemPrompt.strip(), enabled, id, userId) > 0;
    }

    /** Disables a personal template while preserving references from historical conversations. */
    public boolean disablePersonalPromptTemplate(UUID id, long userId) {
        return jdbc.update("""
                UPDATE prompt_template SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_user_id = ? AND scope = 'personal'
                """, id, userId) > 0;
    }

    /** Updates a platform-owned system template after administration authentication. */
    public boolean updateSystemPromptTemplate(
            UUID id, String name, String description, String systemPrompt, boolean enabled
    ) {
        return jdbc.update("""
                UPDATE prompt_template SET name = ?, description = ?, system_prompt = ?, enabled = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND scope = 'system'
                """, name.strip(), description, systemPrompt.strip(), enabled, id) > 0;
    }

    /** Resolves an enabled system or user-owned prompt without exposing another user's template. */
    public Optional<String> findPromptTemplate(UUID templateId, long userId) {
        if (templateId == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT system_prompt FROM prompt_template
                WHERE id = ? AND enabled = TRUE AND (scope = 'system' OR owner_user_id = ?)
                """, (rs, rowNum) -> rs.getString("system_prompt"), templateId, userId)
                .stream().findFirst();
    }

    /** Finalizes a generated assistant message and synchronizes the conversation message count. */
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

    /** Updates an AI title only while it is still temporary, preserving later manual edits. */
    public boolean updateGeneratedTitle(UUID conversationId, String title) {
        int updated = jdbc.update("""
                UPDATE conversation
                SET title = ?, title_source = 'ai', title_updated_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND title_source = 'temporary'
                """, title, conversationId);
        return updated > 0;
    }

    /** Applies a user-owned title and marks it as authoritative. */
    public boolean renameConversation(UUID conversationId, long userId, String title) {
        int updated = jdbc.update("""
                UPDATE conversation
                SET title = ?, title_source = 'manual', title_updated_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND deleted_at IS NULL
                """, title.strip(), conversationId, userId);
        return updated > 0;
    }

    /** Hides an owned conversation without removing messages or usage records. */
    public boolean softDeleteConversation(UUID conversationId, long userId) {
        int updated = jdbc.update("""
                UPDATE conversation
                SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND deleted_at IS NULL
                """, conversationId, userId);
        return updated > 0;
    }

    /** Records title-generation usage and rolls it into the current user's daily aggregate. */
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

    /** Atomically records a completed chat call and updates conversation and daily counters. */
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

    /** Returns the most recently active conversations for the sidebar. */
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

    /** Returns ordered persisted messages after enforcing conversation ownership. */
    public List<ConversationMessage> listMessages(UUID conversationId, long userId) {
        List<ConversationMessage> messages = jdbc.query("""
                SELECT cm.id, cm.role, cm.content, cm.status, cm.input_tokens, cm.output_tokens,
                       cm.created_at, cm.edited_at
                FROM conversation_message cm
                JOIN conversation c ON c.id = cm.conversation_id
                WHERE cm.conversation_id = ? AND c.user_id = ? AND c.deleted_at IS NULL
                  AND cm.superseded_at IS NULL
                ORDER BY cm.sequence_no
                """, (rs, rowNum) -> new ConversationMessage(
                rs.getObject("id", UUID.class),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getInt("input_tokens"),
                rs.getInt("output_tokens"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("edited_at", OffsetDateTime.class),
                List.of()
        ), conversationId, userId);
        Map<UUID, List<ConversationAttachment>> attachments = new HashMap<>();
        jdbc.query("""
                SELECT ma.message_id, fa.id, fa.original_name, fa.media_type, fa.asset_kind, fa.status
                FROM message_attachment ma
                JOIN file_asset fa ON fa.id = ma.file_id
                JOIN conversation_message cm ON cm.id = ma.message_id
                JOIN conversation c ON c.id = cm.conversation_id
                WHERE cm.conversation_id = ? AND c.user_id = ? AND c.deleted_at IS NULL
                ORDER BY ma.message_id, ma.position
                """, rs -> {
            attachments.computeIfAbsent(
                    rs.getObject("message_id", UUID.class), ignored -> new ArrayList<>()
            ).add(new ConversationAttachment(
                    rs.getObject("id", UUID.class), rs.getString("original_name"),
                    rs.getString("media_type"), rs.getString("asset_kind"), rs.getString("status")
            ));
        }, conversationId, userId);
        return messages.stream().map(message -> new ConversationMessage(
                message.id(), message.role(), message.content(), message.status(),
                message.inputTokens(), message.outputTokens(), message.createdAt(), message.editedAt(),
                List.copyOf(attachments.getOrDefault(message.id(), List.of()))
        )).toList();
    }
}
