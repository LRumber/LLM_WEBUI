package com.lmplatform.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests deterministic offline chunking and overlap behavior. */
class DocumentChunkerTest {

    @Test
    void splitsLongTextIntoOrderedOverlappingChunks() {
        String text = "first paragraph. ".repeat(80) + "\n\n" + "second paragraph. ".repeat(80);

        var chunks = new DocumentChunker().chunk(text, 100, 20);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).extracting(chunk -> chunk.index())
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, chunks.size()).boxed().toList()
                );
        assertThat(chunks).allMatch(chunk -> !chunk.content().isBlank() && chunk.tokenCount() > 0);
    }
}
