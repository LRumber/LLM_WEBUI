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

/** Unit tests for asynchronous knowledge indexing orchestration. */
class KnowledgeIndexingServiceTest {

    @Test
    void chunksEmbedsAndCompletesReadyParsedDocument() {
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        AiServiceClient client = mock(AiServiceClient.class);
        UUID documentId = UUID.randomUUID();
        when(repository.findIndexSource(documentId)).thenReturn(Optional.of(
                new KnowledgeIndexSource(documentId, UUID.randomUUID(), "indexable document content")
        ));
        when(client.embed(eq("embedding-bge"), anyList())).thenReturn(List.of(new float[]{1f, 0f}));
        var service = new KnowledgeIndexingService(
                repository, new DocumentChunker(), client,
                new KnowledgeProperties("embedding-bge", 600, 100, 16)
        );

        service.indexDocument(documentId);

        verify(repository).markIndexing(documentId);
        verify(repository).replaceChunks(eq(documentId), anyList(), anyList(), eq("embedding-bge"));
    }
}
