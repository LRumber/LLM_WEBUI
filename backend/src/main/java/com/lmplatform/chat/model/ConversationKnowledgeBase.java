package com.lmplatform.chat.model;

import java.util.UUID;

/** Immutable knowledge-space snapshot bound to one conversation. */
public record ConversationKnowledgeBase(UUID id, String name) {
}
