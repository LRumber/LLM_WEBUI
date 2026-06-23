package com.lmplatform.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.common.model.ModelDefaults;
import com.lmplatform.common.repository.SystemSettingRepository;
import com.lmplatform.knowledge.model.KnowledgeSearchCandidate;
import com.lmplatform.knowledge.model.KnowledgeSearchResult;
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
        SystemSettingRepository settings = mock(SystemSettingRepository.class);
        when(settings.modelDefaults()).thenReturn(new ModelDefaults("", "", ""));
        var service = new KnowledgeSearchService(
                repository, mock(AiServiceClient.class), settings
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

    @Test
    void fallsBackToReadyChunksWhenQueryHasNoDirectMatch() {
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        SystemSettingRepository settings = mock(SystemSettingRepository.class);
        UUID baseId = UUID.randomUUID();
        var candidate = new KnowledgeSearchCandidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "manual.md",
                "knowledge base fallback content", 1, "Overview", 0.15, null
        );
        when(repository.searchKeywordCandidates(baseId, 9L, "unmatched question", 20))
                .thenReturn(List.of());
        when(repository.listFallbackCandidates(baseId, 9L, 5)).thenReturn(List.of(candidate));
        when(settings.modelDefaults()).thenReturn(new ModelDefaults("", "", ""));
        var service = new KnowledgeSearchService(
                repository, mock(AiServiceClient.class), settings
        );

        var results = service.search(baseId, 9L, "unmatched question", 5);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.filename()).isEqualTo("manual.md");
            assertThat(result.score()).isEqualTo(0.15);
        });
        verify(repository).recordRetrieval(
                eq(baseId), eq(9L), eq("unmatched question"), eq("keyword"),
                eq(1), anyInt(), eq(results)
        );
    }

    @Test
    void reranksHybridResultsWhenRerankerIsConfigured() {
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        AiServiceClient client = mock(AiServiceClient.class);
        SystemSettingRepository settings = mock(SystemSettingRepository.class);
        UUID baseId = UUID.randomUUID();
        var first = new KnowledgeSearchCandidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "first.md",
                "first candidate", 1, "First", 0.4, new float[]{1f, 0f}
        );
        var second = new KnowledgeSearchCandidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "second.md",
                "second candidate", 1, "Second", 0.7, new float[]{0f, 1f}
        );
        when(settings.modelDefaults()).thenReturn(new ModelDefaults("embedding-bge", "reranker", ""));
        when(repository.searchKeywordCandidates(baseId, 9L, "query", 20)).thenReturn(List.of(first, second));
        when(repository.listVectorCandidates(baseId, 9L, 2000)).thenReturn(List.of(first, second));
        when(client.embed("embedding-bge", List.of("query"))).thenReturn(List.of(new float[]{1f, 0f}));
        when(client.rerank(eq("reranker"), eq("query"), anyList()))
                .thenReturn(List.of(
                        new AiServiceClient.RerankScore(1, 0.95),
                        new AiServiceClient.RerankScore(0, 0.2)
                ));
        var service = new KnowledgeSearchService(repository, client, settings);

        var results = service.search(baseId, 9L, "query", 2);

        assertThat(results).extracting(KnowledgeSearchResult::filename)
                .containsExactly("second.md", "first.md");
        verify(repository).recordRetrieval(
                eq(baseId), eq(9L), eq("query"), eq("reranked"), eq(2), anyInt(), eq(results)
        );
    }
}
