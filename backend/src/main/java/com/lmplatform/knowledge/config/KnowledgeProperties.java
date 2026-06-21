package com.lmplatform.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Environment-configurable indexing settings suitable for an offline LAN embedding API. */
@ConfigurationProperties("platform.knowledge")
public record KnowledgeProperties(
        String embeddingModel,
        int chunkTokens,
        int overlapTokens,
        int embeddingBatchSize
) {
    public KnowledgeProperties {
        if (chunkTokens <= 0) chunkTokens = 600;
        if (overlapTokens < 0 || overlapTokens >= chunkTokens) overlapTokens = 100;
        if (embeddingBatchSize <= 0) embeddingBatchSize = 16;
    }
}
