package com.lmplatform.chat.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.chat.config.AiServiceProperties;
import com.lmplatform.chat.model.TitleGenerationResult;
import com.lmplatform.chat.repository.ConversationRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TitleGenerationServiceTest {

    @Test
    void generatesNormalizedTitleAndRecordsUsage() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AiServiceClient client = mock(AiServiceClient.class);
        AiServiceProperties properties = new AiServiceProperties(
                "http://localhost:8001", 150, "deepseek-chat"
        );
        TitleGenerationService service = new TitleGenerationService(repository, client, properties);
        UUID conversationId = UUID.randomUUID();

        when(client.generateTitle(eq("deepseek-chat"), any()))
                .thenReturn(new TitleGenerationResult("“离线知识库建设方案。”\n说明", 20, 8));
        when(repository.updateGeneratedTitle(conversationId, "离线知识库建设方案")).thenReturn(true);

        service.generateAsync(conversationId, 1L, "如何建设知识库", "可以分阶段实施");

        verify(repository).updateGeneratedTitle(conversationId, "离线知识库建设方案");
        verify(repository).recordTitleUsage(
                any(UUID.class), eq(1L), eq(conversationId), eq("deepseek-chat"),
                eq(20), eq(8), anyInt()
        );
    }
}
