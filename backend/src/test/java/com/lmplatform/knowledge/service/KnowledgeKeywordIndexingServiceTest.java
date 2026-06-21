package com.lmplatform.knowledge.service;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.knowledge.config.KnowledgeProperties;
import com.lmplatform.knowledge.model.KnowledgeIndexSource;
import com.lmplatform.knowledge.repository.KnowledgeBaseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Confirms parsed documents remain searchable without an embedding service. */
class KnowledgeKeywordIndexingServiceTest {

    @Test
    void completesKeywordChunksWhenEmbeddingIsUnconfigured() {
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        UUID documentId = UUID.randomUUID();
        when(repository.findIndexSource(documentId)).thenReturn(Optional.of(
                new KnowledgeIndexSource(documentId, UUID.randomUUID(), "offline searchable content")
        ));
        var service = new KnowledgeIndexingService(
                repository, new DocumentChunker(), mock(AiServiceClient.class),
                new KnowledgeProperties("", 600, 100, 16)
        );

        service.indexDocument(documentId);

        verify(repository).replaceChunks(eq(documentId), anyList(), eq(List.of()), eq(null));
    }
}
