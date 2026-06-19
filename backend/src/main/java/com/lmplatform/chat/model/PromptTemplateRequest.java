package com.lmplatform.chat.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Validated content shared by personal and system prompt-template maintenance APIs. */
public record PromptTemplateRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description,
        @NotBlank @Size(max = 100_000) String systemPrompt,
        boolean enabled
) {
}
