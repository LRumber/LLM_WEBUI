package com.lmplatform.chat.model;

import java.util.UUID;

/** Selects the last message copied into a new conversation branch. */
public record BranchConversationRequest(UUID messageId) {
}
