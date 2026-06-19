package com.lmplatform.chat.model;

import java.util.UUID;

/** Prompt template projection including ownership scope and administrative enablement. */
public record PromptTemplate(
        UUID id,
        String name,
        String description,
        String systemPrompt,
        String scope,
        boolean enabled
) {
}
