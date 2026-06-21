package com.lmplatform.knowledge.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Document processing metadata safe to expose to knowledge-base members. */
public record KnowledgeDocumentSummary(
        UUID id,
        UUID fileId,
        String originalName,
        String mediaType,
        long sizeBytes,
        String fileStatus,
        String status,
        int chunkCount,
        String errorMessage,
        OffsetDateTime createdAt
) {
}
