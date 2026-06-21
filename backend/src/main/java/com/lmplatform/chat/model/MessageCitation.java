package com.lmplatform.chat.model;

import java.util.UUID;

/** Persisted RAG source shown below one assistant answer. */
public record MessageCitation(
        int number,
        UUID fileId,
        String filename,
        Integer pageNumber,
        String titlePath,
        String content,
        double score
) {
}
