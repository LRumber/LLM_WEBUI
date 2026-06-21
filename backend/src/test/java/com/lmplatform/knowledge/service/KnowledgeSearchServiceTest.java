package com.lmplatform.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.knowledge.config.KnowledgeProperties;
import com.lmplatform.knowledge.model.KnowledgeSearchCandidate;
import com.lmplatform.knowledge.repository.KnowledgeBaseRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tests ranking and offline keyword fallback without an embedding registration. */
class KnowledgeSearchServiceTest {

    @Test
    void returnsKeywordResultsWhenEmbeddingModelIsNotConfigured() {
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        UUID baseId = UUID.randomUUID();
        var candidate = new KnowledgeSearchCandidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "guide.md",
                "offline knowledge content", 1, "Overview", 0.8, null
        );
        when(repository.searchKeywordCandidates(baseId, 9L, "offline", 20))
                .thenReturn(List.of(candidate));
        var service = new KnowledgeSearchService(
                repository, mock(AiServiceClient.class),
                new KnowledgeProperties("", 600, 100, 16)
        );

        var results = service.search(baseId, 9L, " offline ", 5);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.filename()).isEqualTo("guide.md");
            assertThat(result.score()).isEqualTo(0.8);
        });
        verify(repository).recordRetrieval(
                eq(baseId), eq(9L), eq("offline"), eq("keyword"), eq(1), anyInt(), eq(results)
        );
    }
}
