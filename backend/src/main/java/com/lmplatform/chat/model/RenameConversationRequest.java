package com.lmplatform.chat.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Validated payload for a user-supplied conversation title. */
public record RenameConversationRequest(@NotBlank @Size(max = 60) String title) {
}
