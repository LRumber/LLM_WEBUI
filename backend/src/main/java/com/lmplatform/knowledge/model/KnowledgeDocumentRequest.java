package com.lmplatform.knowledge.model;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Associates one user-owned uploaded file with an authorized knowledge base. */
public record KnowledgeDocumentRequest(@NotNull UUID fileId) {
}
