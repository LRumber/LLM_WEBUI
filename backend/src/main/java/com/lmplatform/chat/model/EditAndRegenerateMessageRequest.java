package com.lmplatform.chat.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Replacement content plus generation options for editing a user turn. */
public record EditAndRegenerateMessageRequest(
        @NotBlank @Size(max = 100_000) String content,
        @NotBlank String model,
        boolean thinkingEnabled
) {
}
