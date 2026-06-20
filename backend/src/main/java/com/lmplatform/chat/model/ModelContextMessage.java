package com.lmplatform.chat.model;

import java.util.List;

/** Active conversation message plus attachments needed for OpenAI multimodal content parts. */
public record ModelContextMessage(
        String role,
        String content,
        List<ModelMessageAttachment> attachments
) {
}
