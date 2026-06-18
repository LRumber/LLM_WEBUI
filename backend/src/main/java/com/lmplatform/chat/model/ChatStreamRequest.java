package com.lmplatform.chat.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ChatStreamRequest(
        UUID conversationId,
        @NotBlank String model,
        @NotBlank @Size(max = 100_000) String content
) {
}
