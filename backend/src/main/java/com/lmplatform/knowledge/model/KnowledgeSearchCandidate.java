package com.lmplatform.knowledge.model;

import java.util.UUID;

/** Internal searchable chunk with optional portable embedding values. */
public record KnowledgeSearchCandidate(
        UUID chunkId,
        UUID documentId,
        UUID fileId,
        String filename,
        String content,
        Integer pageNumber,
        String titlePath,
        double keywordScore,
        float[] embedding
) {
}
