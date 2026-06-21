package com.lmplatform.knowledge.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Editable knowledge-base fields; ownership and space type are assigned by the service. */
public record KnowledgeBaseRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        @Size(max = 64) String icon
) {
}
