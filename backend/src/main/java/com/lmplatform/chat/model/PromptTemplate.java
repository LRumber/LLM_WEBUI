package com.lmplatform.chat.model;

import java.util.UUID;

/** User-visible prompt template available to seed a conversation system instruction. */
public record PromptTemplate(UUID id, String name, String description, String systemPrompt, String scope) {
}
