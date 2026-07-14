package com.lmplatform.chat.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import java.util.List;

/**
 * Request accepted by the persisted streaming-chat endpoint.
 *
 * @param conversationId existing conversation, or {@code null} to create one
 * @param model registered model key
 * @param content current user message
 * @param modelContent optional model-only prompt with quoted context; not persisted as chat history
 * @param templateId optional system prompt template used when creating a conversation
 * @param attachmentIds user-owned files linked to the persisted user message
 * @param knowledgeBaseIds knowledge spaces selected only when creating a conversation
 * @param thinkingEnabled whether the compatible model should expose its reasoning mode
 */
public record ChatStreamRequest(
        UUID conversationId,
        @NotBlank String model,
        @NotBlank @Size(max = 100_000) String content,
        @Size(max = 100_000) String modelContent,
        UUID templateId,
        @Size(max = 20) List<UUID> attachmentIds,
        @Size(max = 5) List<UUID> knowledgeBaseIds,
        boolean thinkingEnabled
) {
}
