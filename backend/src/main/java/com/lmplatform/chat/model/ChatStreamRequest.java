package com.lmplatform.chat.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request accepted by the persisted streaming-chat endpoint.
 *
 * @param conversationId existing conversation, or {@code null} to create one
 * @param model registered model key
 * @param content current user message
 */
public record ChatStreamRequest(
        UUID conversationId,
        @NotBlank String model,
        @NotBlank @Size(max = 100_000) String content
) {
}
