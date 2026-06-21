package com.lmplatform.knowledge.model;

import java.util.UUID;

/** Ranked retrieval result with transparent component scores and source metadata. */
public record KnowledgeSearchResult(
        UUID chunkId,
        UUID documentId,
        UUID fileId,
        String filename,
        String content,
        Integer pageNumber,
        String titlePath,
        double keywordScore,
        double vectorScore,
        double score
) {
}
