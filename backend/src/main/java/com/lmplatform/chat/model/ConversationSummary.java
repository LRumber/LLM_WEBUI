package com.lmplatform.chat.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Compact conversation projection used by the sidebar history list. */
public record ConversationSummary(
        UUID id,
        String title,
        String model,
        int messageCount,
        String preview,
        UUID promptTemplateId,
        String promptTemplateName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
