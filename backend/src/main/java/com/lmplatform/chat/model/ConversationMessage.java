package com.lmplatform.chat.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Read model returned when a client restores persisted conversation history. */
public record ConversationMessage(
        UUID id,
        String role,
        String content,
        String status,
        int inputTokens,
        int outputTokens,
        String reasoningContent,
        Integer reasoningDurationMs,
        OffsetDateTime createdAt,
        OffsetDateTime editedAt,
        Integer versionIndex,
        Integer versionCount,
        List<ConversationAttachment> attachments,
        List<MessageCitation> citations
) {
}
