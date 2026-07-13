package com.lmplatform.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.auth.model.AuthenticatedShadowUser;
import com.lmplatform.chat.config.LegacyChatImportProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Imports the legacy Streamlit JSON chat history once after a user signs in. */
@Service
public class LegacyChatImportService {

    private static final Logger log = LoggerFactory.getLogger(LegacyChatImportService.class);
    private static final Set<String> VALID_ROLES = Set.of("system", "user", "assistant");

    private final LegacyChatImportProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public LegacyChatImportService(
            LegacyChatImportProperties properties,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    public void importAfterLogin(AuthenticatedShadowUser user) {
        if (!properties.enabled()) return;
        if (user.idNo() == null || user.idNo().isBlank()) return;
        if (alreadyImported(user.id())) return;
        try {
            ImportResult result = importForUser(user);
            if (result.conversationCount() > 0) {
                log.info("Imported legacy chat history for user {}: {} conversations, {} messages",
                        user.id(), result.conversationCount(), result.messageCount());
            }
        } catch (Exception exception) {
            recordFailure(user, sourcePath(user.idNo()).toString(), exception);
            log.warn("Legacy chat import failed for user {}", user.id(), exception);
        }
    }

    private boolean alreadyImported(long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM legacy_chat_import WHERE user_id = ? AND status = 'success'",
                Integer.class, userId
        );
        return count != null && count > 0;
    }

    @Transactional
    public ImportResult importForUser(AuthenticatedShadowUser user) throws IOException {
        Path historyPath = sourcePath(user.idNo());
        if (!Files.isRegularFile(historyPath)) {
            return new ImportResult(0, 0);
        }

        JsonNode root = objectMapper.readTree(historyPath.toFile());
        List<LegacyConversation> conversations = conversations(root);
        int conversationCount = 0;
        int messageCount = 0;
        for (LegacyConversation conversation : conversations) {
            if (conversation.messages().isEmpty()) continue;
            UUID conversationId = stableUuid("conversation:" + user.idNo() + ":" + conversation.title());
            OffsetDateTime createdAt = conversation.createdAt();
            OffsetDateTime updatedAt = createdAt.plusSeconds(Math.max(0, conversation.messages().size() - 1));
            jdbc.update("""
                    INSERT INTO conversation (
                        id, user_id, model_key, title, message_count, title_source,
                        title_updated_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'manual', ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, conversationId, user.id(), properties.resolvedDefaultModel(),
                    truncate(conversation.title(), 255), conversation.messages().size(),
                    timestamp(createdAt), timestamp(createdAt), timestamp(updatedAt));
            conversationCount++;
            int sequence = 1;
            for (LegacyMessage message : conversation.messages()) {
                UUID messageId = stableUuid("message:" + user.idNo() + ":" + conversation.title() + ":" + sequence);
                OffsetDateTime messageTime = createdAt.plusSeconds(sequence - 1L);
                jdbc.update("""
                        INSERT INTO conversation_message (
                            id, conversation_id, sequence_no, role, content, status,
                            reasoning_content, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'completed', ?, ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """, messageId, conversationId, sequence, message.role(), message.content(),
                        message.reasoningContent(), timestamp(messageTime), timestamp(messageTime));
                sequence++;
                messageCount++;
            }
        }
        recordSuccess(user, historyPath.toString(), conversationCount, messageCount);
        return new ImportResult(conversationCount, messageCount);
    }

    private Path sourcePath(String userKey) {
        String root = properties.dataRoot();
        if (root == null || root.isBlank()) {
            return Path.of(userKey, "history_chat.json");
        }
        return Path.of(root, userKey, "history_chat.json");
    }

    private List<LegacyConversation> conversations(JsonNode root) {
        List<LegacyConversation> result = new ArrayList<>();
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        JsonNode titleNode = root.path("title");
        if (titleNode.isArray()) {
            titleNode.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) titles.add(node.asText());
            });
        }
        addObjectKeys(titles, root.path("history_messages"));
        addObjectKeys(titles, root.path("think_message"));

        for (String title : titles) {
            Optional<JsonNode> payload = payloadForTitle(root, title);
            if (payload.isEmpty()) continue;
            List<LegacyMessage> messages = messages(payload.get().path("messages"));
            if (!messages.isEmpty()) {
                result.add(new LegacyConversation(
                        normalizeTitle(title),
                        parseTime(payload.get().path("time").asText(null)),
                        messages
                ));
            }
        }
        return result;
    }

    private void addObjectKeys(Set<String> titles, JsonNode node) {
        if (!node.isObject()) return;
        node.fieldNames().forEachRemaining(title -> {
            if (title != null && !title.isBlank()) titles.add(title);
        });
    }

    private Optional<JsonNode> payloadForTitle(JsonNode root, String title) {
        JsonNode thinkPayload = root.path("think_message").path(title);
        if (thinkPayload.isObject() && thinkPayload.path("messages").isArray()) {
            return Optional.of(thinkPayload);
        }
        JsonNode historyPayload = root.path("history_messages").path(title);
        if (historyPayload.isObject() && historyPayload.path("messages").isArray()) {
            return Optional.of(historyPayload);
        }
        return Optional.empty();
    }

    private List<LegacyMessage> messages(JsonNode node) {
        List<LegacyMessage> result = new ArrayList<>();
        if (!node.isArray()) return result;
        for (JsonNode message : node) {
            if (!message.isObject()) continue;
            String role = message.path("role").asText("user").strip().toLowerCase();
            if (!VALID_ROLES.contains(role)) role = "user";
            String content = content(message.path("content"));
            String reasoning = textOrNull(message.path("reasoning_content"));
            result.add(new LegacyMessage(role, content, reasoning));
        }
        return result;
    }

    private String content(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode part : node) {
                if (part.isTextual()) {
                    parts.add(part.asText());
                } else if (part.isObject() && "text".equals(part.path("type").asText())) {
                    String text = part.path("text").asText("");
                    if (!text.isBlank()) parts.add(text);
                }
            }
            return String.join("\n", parts);
        }
        if (node.isMissingNode() || node.isNull()) return "";
        return node.asText("");
    }

    private String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }

    private OffsetDateTime parseTime(String value) {
        if (value != null && !value.isBlank()) {
            for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
                try {
                    return LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern(pattern))
                            .atZone(ZoneId.systemDefault())
                            .toOffsetDateTime();
                } catch (java.time.format.DateTimeParseException ignored) {
                    // Try the next legacy timestamp format.
                }
            }
        }
        return OffsetDateTime.now();
    }

    private String normalizeTitle(String title) {
        String normalized = title.replace("\"", "").strip();
        return normalized.isBlank() ? "旧系统历史对话" : normalized;
    }

    private String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    private Timestamp timestamp(OffsetDateTime time) {
        return Timestamp.from(time.toInstant());
    }

    private UUID stableUuid(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            bytes[6] &= 0x0f;
            bytes[6] |= 0x50;
            bytes[8] &= 0x3f;
            bytes[8] |= (byte) 0x80;
        return UUID.nameUUIDFromBytes(bytes);
        } catch (NoSuchAlgorithmException exception) {
            return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void recordSuccess(
            AuthenticatedShadowUser user, String sourcePath, int conversationCount, int messageCount
    ) {
        jdbc.update("""
                INSERT INTO legacy_chat_import (
                    user_id, legacy_user_key, source_path, status, conversation_count, message_count,
                    error_message, imported_at, updated_at
                ) VALUES (?, ?, ?, 'success', ?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id) DO UPDATE SET
                    legacy_user_key = EXCLUDED.legacy_user_key,
                    source_path = EXCLUDED.source_path,
                    status = 'success',
                    conversation_count = EXCLUDED.conversation_count,
                    message_count = EXCLUDED.message_count,
                    error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                """, user.id(), user.idNo(), sourcePath, conversationCount, messageCount);
    }

    private void recordFailure(AuthenticatedShadowUser user, String sourcePath, Exception exception) {
        jdbc.update("""
                INSERT INTO legacy_chat_import (
                    user_id, legacy_user_key, source_path, status, error_message, updated_at
                ) VALUES (?, ?, ?, 'failed', ?, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id) DO UPDATE SET
                    source_path = EXCLUDED.source_path,
                    status = 'failed',
                    error_message = EXCLUDED.error_message,
                    updated_at = CURRENT_TIMESTAMP
                """, user.id(), user.idNo(), sourcePath,
                truncate(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), 1024));
    }

    public record ImportResult(int conversationCount, int messageCount) {
    }

    private record LegacyConversation(String title, OffsetDateTime createdAt, List<LegacyMessage> messages) {
    }

    private record LegacyMessage(String role, String content, String reasoningContent) {
    }
}
