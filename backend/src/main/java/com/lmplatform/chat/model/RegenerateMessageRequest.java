package com.lmplatform.chat.model;

import jakarta.validation.constraints.NotBlank;

/** Selects the registered model used to replace one assistant answer. */
public record RegenerateMessageRequest(@NotBlank String model) {
}
