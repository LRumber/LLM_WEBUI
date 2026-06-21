package com.lmplatform.knowledge.model;

import java.util.UUID;

/** Parsed source text required by the asynchronous knowledge indexing worker. */
public record KnowledgeIndexSource(UUID documentId, UUID fileId, String text) {
}
