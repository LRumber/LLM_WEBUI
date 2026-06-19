package com.lmplatform.chat.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Replacement content for one owned user message; later branch messages become superseded. */
public record EditMessageRequest(@NotBlank @Size(max = 100_000) String content) {
}
