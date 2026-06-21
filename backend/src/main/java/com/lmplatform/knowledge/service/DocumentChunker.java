package com.lmplatform.knowledge.service;

import com.lmplatform.knowledge.model.KnowledgeChunk;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Produces deterministic paragraph-aware chunks without requiring an online tokenizer. */
@Component
public class DocumentChunker {

    public List<KnowledgeChunk> chunk(String text, int targetTokens, int overlapTokens) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").strip();
        if (normalized.isEmpty()) return List.of();
        int targetChars = Math.max(200, targetTokens * 2);
        int overlapChars = Math.max(0, overlapTokens * 2);
        List<KnowledgeChunk> result = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int desiredEnd = Math.min(normalized.length(), start + targetChars);
            int end = findBoundary(normalized, start, desiredEnd);
            String content = normalized.substring(start, end).strip();
            if (!content.isEmpty()) {
                result.add(new KnowledgeChunk(result.size(), content, estimateTokens(content)));
            }
            if (end >= normalized.length()) break;
            int next = Math.max(start + 1, end - overlapChars);
            start = skipWhitespace(normalized, next);
        }
        return result;
    }

    private int findBoundary(String text, int start, int desiredEnd) {
        if (desiredEnd >= text.length()) return text.length();
        int paragraph = text.lastIndexOf("\n\n", desiredEnd);
        if (paragraph > start + desiredEnd / 3) return paragraph;
        int sentence = Math.max(text.lastIndexOf('。', desiredEnd), text.lastIndexOf('.', desiredEnd));
        return sentence > start + 100 ? sentence + 1 : desiredEnd;
    }

    private int skipWhitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        return index;
    }

    private int estimateTokens(String content) {
        // Two characters per token is conservative for mixed Chinese and Latin enterprise documents.
        return Math.max(1, (content.length() + 1) / 2);
    }
}
