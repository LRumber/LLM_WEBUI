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
 * @param templateId optional system prompt template used when creating a conversation
 * @param attachmentIds user-owned files linked to the persisted user message
 */
public record ChatStreamRequest(
        UUID conversationId,
        @NotBlank String model,
        @NotBlank @Size(max = 100_000) String content,
        UUID templateId,
        @Size(max = 20) List<UUID> attachmentIds
) {
}
