package com.lmplatform.knowledge.model;

/** One stable, ordered text segment paired with its approximate token count. */
public record KnowledgeChunk(int index, String content, int tokenCount) {
}
