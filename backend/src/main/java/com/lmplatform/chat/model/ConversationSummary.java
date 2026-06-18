package com.lmplatform.chat.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConversationSummary(
        UUID id,
        String title,
        String model,
        int messageCount,
        String preview,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
