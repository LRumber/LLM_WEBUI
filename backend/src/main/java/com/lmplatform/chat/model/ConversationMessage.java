package com.lmplatform.chat.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConversationMessage(
        UUID id,
        String role,
        String content,
        String status,
        int inputTokens,
        int outputTokens,
        OffsetDateTime createdAt
) {
}
