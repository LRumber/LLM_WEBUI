package com.lmplatform.common.model;

/** Platform-selected model defaults managed from the administration console. */
public record ModelDefaults(
        String embeddingModel,
        String rerankerModel,
        String speechModel
) {
}
