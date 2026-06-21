package com.lmplatform.knowledge.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Safe knowledge-base metadata returned to an authorized browser client. */
public record KnowledgeBaseSummary(
        UUID id,
        String name,
        String description,
        String icon,
        String spaceType,
        String memberRole,
        int documentCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
