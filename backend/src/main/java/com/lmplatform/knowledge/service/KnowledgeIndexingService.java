package com.lmplatform.knowledge.service;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.common.repository.SystemSettingRepository;
import com.lmplatform.knowledge.config.KnowledgeProperties;
import com.lmplatform.knowledge.model.KnowledgeChunk;
import com.lmplatform.knowledge.repository.KnowledgeBaseRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Runs durable chunking and embedding outside request and file-parser threads. */
@Service
public class KnowledgeIndexingService {

    private final KnowledgeBaseRepository repository;
    private final DocumentChunker chunker;
    private final AiServiceClient aiServiceClient;
    private final KnowledgeProperties properties;
    private final SystemSettingRepository settings;

    public KnowledgeIndexingService(
            KnowledgeBaseRepository repository,
            DocumentChunker chunker,
            AiServiceClient aiServiceClient,
            KnowledgeProperties properties,
            SystemSettingRepository settings
    ) {
        this.repository = repository;
        this.chunker = chunker;
        this.aiServiceClient = aiServiceClient;
        this.properties = properties;
        this.settings = settings;
    }

    @Async("knowledgeTaskExecutor")
    public void indexDocument(UUID documentId) {
        indexNow(documentId);
    }

    /** File parsing completion wakes every knowledge document linked to the same private asset. */
    @Async("knowledgeTaskExecutor")
    public void indexFile(UUID fileId) {
        repository.findActiveDocumentIds(fileId).forEach(this::indexNow);
    }

    private void indexNow(UUID documentId) {
        var source = repository.findIndexSource(documentId);
        if (source.isEmpty()) return;
        repository.markIndexing(documentId);
        try {
            List<KnowledgeChunk> chunks = chunker.chunk(
                    source.get().text(), properties.chunkTokens(), properties.overlapTokens()
            );
            if (chunks.isEmpty()) throw new IllegalStateException("Document contains no indexable text");
            List<float[]> vectors = new ArrayList<>();
            String embeddingModel = settings.modelDefaults().embeddingModel();
            boolean embeddingConfigured = embeddingModel != null && !embeddingModel.isBlank();
            if (embeddingConfigured) {
                for (int start = 0; start < chunks.size(); start += properties.embeddingBatchSize()) {
                    int end = Math.min(chunks.size(), start + properties.embeddingBatchSize());
                    vectors.addAll(aiServiceClient.embed(
                            embeddingModel,
                            chunks.subList(start, end).stream().map(KnowledgeChunk::content).toList()
                    ));
                }
                validateDimensions(vectors);
            }
            // Keyword-only indexing is a complete offline fallback, not a failed intermediate state.
            repository.replaceChunks(
                    documentId, chunks, vectors,
                    embeddingConfigured ? embeddingModel : null
            );
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "Knowledge indexing failed" : exception.getMessage();
            repository.failIndexing(documentId, message.substring(0, Math.min(message.length(), 1000)));
        }
    }

    private void validateDimensions(List<float[]> vectors) {
        if (vectors.isEmpty() || vectors.getFirst().length == 0) {
            throw new IllegalStateException("Embedding model returned an empty vector");
        }
        int dimension = vectors.getFirst().length;
        if (vectors.stream().anyMatch(vector -> vector.length != dimension)) {
            throw new IllegalStateException("Embedding model returned inconsistent vector dimensions");
        }
    }
}
