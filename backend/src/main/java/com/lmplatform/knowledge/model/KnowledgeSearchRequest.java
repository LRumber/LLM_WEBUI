package com.lmplatform.knowledge.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Bounded query submitted by the knowledge retrieval test page or future chat orchestration. */
public record KnowledgeSearchRequest(
        @NotBlank @Size(max = 2000) String query,
        @Min(1) @Max(20) Integer topK
) {
    public int resolvedTopK() {
        return topK == null ? 8 : topK;
    }
}
