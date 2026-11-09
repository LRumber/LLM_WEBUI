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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

/** Unit tests for the chat orchestration boundary without network or database dependencies. */
class ChatServiceTest {

    @Test
    void unavailableOrEmptyModelResponseProducesErrorEventAndPersistsFailedState() throws Exception {
        for (Exception failure : List.of(new java.io.IOException("Upstream returned 503: internal detail"),
                new org.springframework.web.client.ResourceAccessException("Connection refused"),
                new java.io.IOException("empty_model_response"))) {
            ConversationRepository repository = mock(ConversationRepository.class);
            AiServiceClient client = mock(AiServiceClient.class);
            ChatService service = new ChatService(repository, client, new ObjectMapper(), mock(TitleGenerationService.class),
                    new AiServiceProperties("http://localhost:8001", 150, "test", 40),
                    mock(ObjectStorageService.class), mock(KnowledgeBaseService.class), mock(KnowledgeSearchService.class));
            UUID conversationId = UUID.randomUUID();
            UUID previousId = UUID.randomUUID();
            UUID replacementId = UUID.randomUUID();
            when(repository.prepareAssistantRegeneration(conversationId, previousId, 1L)).thenReturn(true);
            when(repository.appendMessage(conversationId, "assistant", "", "generating")).thenReturn(replacementId);
            when(repository.modelContextMessages(conversationId, 40)).thenReturn(List.of(
                    new ModelContextMessage(1, "user", "hello", List.of())));
            doAnswer(invocation -> {
                if ("empty_model_response".equals(failure.getMessage())) return null;
                throw failure;
            }).when(client).streamChat(eq("test"), any(), eq(false), any());
            var output = new ByteArrayOutputStream();
            service.regenerate(1L, conversationId, previousId, "test", output);
            assertThat(output.toString(StandardCharsets.UTF_8)).contains("\"type\":\"error\"", "当前模型暂时不可用")
                    .doesNotContain("Connection refused", "internal detail");
            verify(repository).finishAssistantMessage(replacementId, "", "failed", 0, 0, null, null, "stream_error");
        }
    }

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
                .thenReturn(List.of(new ModelContextMessage(1, "user", "你好", List.of())));

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
        service.stream(1L, new ChatStreamRequest(
                null, "deepseek-chat", "你好", null, templateId, List.of(), List.of(knowledgeBaseId), true
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
                .thenReturn(List.of(new ModelContextMessage(1, "user", "你好", List.of())));
        doAnswer(invocation -> {
            AiServiceClient.EventConsumer consumer = invocation.getArgument(3);
            consumer.accept(objectMapper.readTree("{\"type\":\"delta\",\"content\":\"新回答\"}"));
            consumer.accept(objectMapper.readTree("{\"type\":\"done\"}"));
            return null;
        }).when(client).streamChat(eq("deepseek-chat"), any(), eq(false), any());

        service.regenerate(
                1L, conversationId, oldAssistantId, "deepseek-chat", new ByteArrayOutputStream()
        );

        verify(repository).finishAssistantMessage(
                replacementId, "新回答", "completed", 0, 0, null, null, null
        );
        verify(repository).recordCompletedUsage(
                any(UUID.class), eq(1L), eq(conversationId), eq("deepseek-chat"),
                eq(0), eq(0), any(), anyInt(), eq(false)
        );
    }

    /** Confirms editing a user turn stays inside the current conversation and streams a new answer. */
    @Test
    void editsUserMessageAndRegeneratesInCurrentConversation() throws Exception {
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
        UUID originalUserId = UUID.randomUUID();
        UUID editedUserId = UUID.randomUUID();
        UUID assistantId = UUID.randomUUID();
        UUID versionGroupId = UUID.randomUUID();
        when(repository.replaceUserMessageForRegeneration(
                conversationId, originalUserId, 1L, "edited question"
        )).thenReturn(new ConversationRepository.MessageVersion(versionGroupId, 2, editedUserId));
        when(repository.appendVersionedMessage(
                conversationId, "assistant", "", "generating", versionGroupId, 2
        ))
                .thenReturn(assistantId);
        when(repository.modelContextMessages(conversationId, 40))
                .thenReturn(List.of(new ModelContextMessage(1, "user", "edited question", List.of())));
        doAnswer(invocation -> {
            AiServiceClient.EventConsumer consumer = invocation.getArgument(3);
            consumer.accept(objectMapper.readTree("{\"type\":\"delta\",\"content\":\"new answer\"}"));
            consumer.accept(objectMapper.readTree("{\"type\":\"done\"}"));
            return null;
        }).when(client).streamChat(eq("deepseek-chat"), any(), eq(true), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.editAndRegenerate(
                1L, conversationId, originalUserId,
                "edited question", "deepseek-chat", true, output
        );

        String stream = output.toString(StandardCharsets.UTF_8);
        assertThat(stream).contains(
                "edit_metadata", editedUserId.toString(), "\"version_index\":2", conversationId.toString()
        );
        verify(repository).replaceUserMessageForRegeneration(
                conversationId, originalUserId, 1L, "edited question"
        );
        verify(repository).appendVersionedMessage(
                conversationId, "assistant", "", "generating", versionGroupId, 2
        );
        verify(repository).finishAssistantMessage(
                assistantId, "new answer", "completed", 0, 0, null, null, null
        );
    }

    /** Confirms long conversations are summarized before sending while visible history remains untouched. */
    @Test
    void summarizesOlderContextWhenPromptWouldExceedModelWindow() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AiServiceClient client = mock(AiServiceClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatService service = new ChatService(
                repository, client, objectMapper, mock(TitleGenerationService.class),
                new AiServiceProperties("http://localhost:8001", 150, "deepseek-chat", 40),
                mock(ObjectStorageService.class), mock(KnowledgeBaseService.class), mock(KnowledgeSearchService.class)
        );
        UUID conversationId = UUID.randomUUID();
        UUID assistantId = UUID.randomUUID();
        when(repository.belongsToUser(conversationId, 1L)).thenReturn(true);
        when(repository.appendMessage(conversationId, "user", "继续", "completed")).thenReturn(UUID.randomUUID());
        when(repository.appendMessage(conversationId, "assistant", "", "generating")).thenReturn(assistantId);
        when(client.getModel("deepseek-chat")).thenReturn(objectMapper.readTree("{\"context_window\":4096}"));
        List<ModelContextMessage> context = new java.util.ArrayList<>();
        for (int index = 1; index <= 18; index++) {
            context.add(new ModelContextMessage(
                    index, index % 2 == 0 ? "assistant" : "user",
                    "第" + index + "条历史。" + "很长的背景资料。".repeat(160),
                    List.of()
            ));
        }
        context.add(new ModelContextMessage(19, "user", "继续", List.of()));
        when(repository.modelContextMessagesAfter(conversationId, 0, 200)).thenReturn(context);
        when(client.generateTitle(eq("deepseek-chat"), any())).thenReturn(
                new com.lmplatform.chat.model.TitleGenerationResult("已压缩的早期上下文摘要", 120, 30)
        );
        doAnswer(invocation -> {
            AiServiceClient.EventConsumer consumer = invocation.getArgument(3);
            consumer.accept(objectMapper.readTree("{\"type\":\"delta\",\"content\":\"回答\"}"));
            consumer.accept(objectMapper.readTree("{\"type\":\"done\"}"));
            return null;
        }).when(client).streamChat(eq("deepseek-chat"), any(), eq(false), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.stream(1L, new ChatStreamRequest(
                conversationId, "deepseek-chat", "继续", null, null, List.of(), List.of(), false
        ), output);

        verify(repository).saveContextSummary(conversationId, "已压缩的早期上下文摘要", 7);
        String stream = output.toString(StandardCharsets.UTF_8);
        assertThat(stream).contains("\"type\":\"context_compaction\"");
        assertThat(stream).contains("正在压缩上下文，请稍等");
        assertThat(stream).contains("上下文压缩完成，正在生成回复");
        ArgumentCaptor<List<Map<String, ?>>> messages = ArgumentCaptor.forClass(List.class);
        verify(client).streamChat(eq("deepseek-chat"), messages.capture(), eq(false), any());
        assertThat(messages.getValue().stream()
                .anyMatch(message -> String.valueOf(message.get("content")).contains("已压缩的早期上下文摘要")))
                .isTrue();
        verify(repository).finishAssistantMessage(
                assistantId, "回答", "completed", 0, 0, null, null, null
        );
    }
}
