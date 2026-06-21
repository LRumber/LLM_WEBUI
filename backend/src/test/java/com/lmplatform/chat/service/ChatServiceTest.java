package com.lmplatform.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.chat.config.AiServiceProperties;
import com.lmplatform.chat.model.ChatStreamRequest;
import com.lmplatform.chat.model.ModelContextMessage;
import com.lmplatform.chat.model.PromptTemplate;
import com.lmplatform.chat.repository.ConversationRepository;
import com.lmplatform.file.service.ObjectStorageService;
import com.lmplatform.knowledge.service.KnowledgeBaseService;
import com.lmplatform.knowledge.service.KnowledgeSearchService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the chat orchestration boundary without network or database dependencies. */
class ChatServiceTest {

    /** Confirms event forwarding, persistence, accounting, and title scheduling for a new chat. */
    @Test
    void streamsEventsAndPersistsCompletedResponse() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AiServiceClient client = mock(AiServiceClient.class);
        TitleGenerationService titleGenerationService = mock(TitleGenerationService.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        KnowledgeSearchService knowledgeSearchService = mock(KnowledgeSearchService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatService service = new ChatService(
                repository, client, objectMapper, titleGenerationService,
                new AiServiceProperties("http://localhost:8001", 150, "deepseek-chat", 40),
                mock(ObjectStorageService.class), knowledgeBaseService, knowledgeSearchService
        );
        UUID conversationId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();

        when(repository.ensureDevelopmentUser()).thenReturn(1L);
        when(repository.findPromptTemplate(templateId, 1L)).thenReturn(Optional.of(
                new PromptTemplate(templateId, "Technical Assistant", "", "Be precise", "personal", true)
        ));
        when(repository.createConversation(
                1L, "deepseek-chat", "你好", templateId, "Technical Assistant"
        ))
                .thenReturn(conversationId);
        when(repository.appendMessage(conversationId, "user", "你好", "completed"))
                .thenReturn(userMessageId);
        when(repository.appendMessage(conversationId, "assistant", "", "generating"))
                .thenReturn(assistantMessageId);
        when(repository.modelContextMessages(conversationId, 40))
                .thenReturn(List.of(new ModelContextMessage("user", "你好", List.of())));

        doAnswer(invocation -> {
            AiServiceClient.EventConsumer consumer = invocation.getArgument(3);
            consumer.accept(objectMapper.readTree("{\"type\":\"reasoning_delta\",\"content\":\"先分析\"}"));
            consumer.accept(objectMapper.readTree("{\"type\":\"delta\",\"content\":\"你\"}"));
            consumer.accept(objectMapper.readTree("{\"type\":\"delta\",\"content\":\"好\"}"));
            consumer.accept(objectMapper.readTree("""
                    {"type":"usage","prompt_tokens":3,"completion_tokens":2,"total_tokens":5}
                    """));
            consumer.accept(objectMapper.readTree("{\"type\":\"done\"}"));
            return null;
        }).when(client).streamChat(eq("deepseek-chat"), any(), eq(true), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.stream(new ChatStreamRequest(
                null, "deepseek-chat", "你好", templateId, List.of(), List.of(knowledgeBaseId), true
        ), output);

        String stream = output.toString(StandardCharsets.UTF_8);
        assertThat(stream).contains("metadata", conversationId.toString(), "\"content\":\"你\"");
        assertThat(stream).contains(
                "\"type\":\"reasoning_delta\"", "\"type\":\"reasoning_done\"",
                "\"total_tokens\":5", "\"type\":\"done\""
        );
        verify(repository).finishAssistantMessage(
                eq(assistantMessageId), eq("你好"), eq("completed"), eq(3), eq(2),
                eq("先分析"), anyInt(), eq(null)
        );
        verify(repository).appendMessage(conversationId, "system", "Be precise", "completed");
        verify(knowledgeBaseService).find(knowledgeBaseId, 1L);
        verify(repository).bindKnowledgeBases(conversationId, 1L, List.of(knowledgeBaseId));
        verify(repository).recordCompletedUsage(
                any(UUID.class), eq(1L), eq(conversationId), eq("deepseek-chat"),
                eq(3), eq(2), anyInt(), anyInt(), eq(true)
        );
        verify(titleGenerationService).generateAsync(conversationId, 1L, "你好", "你好");
    }

    /** Confirms regeneration supersedes the selected answer and does not append another user turn. */
    @Test
    void regeneratesAssistantFromExistingContext() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AiServiceClient client = mock(AiServiceClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatService service = new ChatService(
                repository, client, objectMapper, mock(TitleGenerationService.class),
                new AiServiceProperties("http://localhost:8001", 150, "deepseek-chat", 40),
                mock(ObjectStorageService.class), mock(KnowledgeBaseService.class),
                mock(KnowledgeSearchService.class)
        );
        UUID conversationId = UUID.randomUUID();
        UUID oldAssistantId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID();
        when(repository.ensureDevelopmentUser()).thenReturn(1L);
        when(repository.prepareAssistantRegeneration(conversationId, oldAssistantId, 1L)).thenReturn(true);
        when(repository.appendMessage(conversationId, "assistant", "", "generating"))
                .thenReturn(replacementId);
        when(repository.modelContextMessages(conversationId, 40))
                .thenReturn(List.of(new ModelContextMessage("user", "你好", List.of())));
        doAnswer(invocation -> {
            AiServiceClient.EventConsumer consumer = invocation.getArgument(3);
            consumer.accept(objectMapper.readTree("{\"type\":\"delta\",\"content\":\"新回答\"}"));
            consumer.accept(objectMapper.readTree("{\"type\":\"done\"}"));
            return null;
        }).when(client).streamChat(eq("deepseek-chat"), any(), eq(false), any());

        service.regenerate(
                conversationId, oldAssistantId, "deepseek-chat", new ByteArrayOutputStream()
        );

        verify(repository).finishAssistantMessage(
                replacementId, "新回答", "completed", 0, 0, null, null, null
        );
        verify(repository).recordCompletedUsage(
                any(UUID.class), eq(1L), eq(conversationId), eq("deepseek-chat"),
                eq(0), eq(0), any(), anyInt(), eq(false)
        );
    }
}
