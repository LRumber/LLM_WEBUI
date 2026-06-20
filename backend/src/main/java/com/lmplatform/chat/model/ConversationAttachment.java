package com.lmplatform.chat.model;

import java.util.UUID;

/** Safe attachment metadata displayed with its persisted user message. */
public record ConversationAttachment(
        UUID id,
        String originalName,
        String mediaType,
        String assetKind,
        String status
) {
}
