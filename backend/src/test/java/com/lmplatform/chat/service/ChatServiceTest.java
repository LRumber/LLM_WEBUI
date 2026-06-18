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
import com.lmplatform.chat.model.ChatStreamRequest;
import com.lmplatform.chat.repository.ConversationRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatServiceTest {

    @Test
    void streamsEventsAndPersistsCompletedResponse() throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        AiServiceClient client = mock(AiServiceClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatService service = new ChatService(repository, client, objectMapper);
        UUID conversationId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();

        when(repository.ensureDevelopmentUser()).thenReturn(1L);
        when(repository.createConversation(1L, "deepseek-chat", "你好")).thenReturn(conversationId);
        when(repository.appendMessage(conversationId, "user", "你好", "completed"))
                .thenReturn(userMessageId);
        when(repository.appendMessage(conversationId, "assistant", "", "generating"))
                .thenReturn(assistantMessageId);
        when(repository.modelMessages(conversationId))
                .thenReturn(List.of(Map.of("role", "user", "content", "你好")));

        doAnswer(invocation -> {
            AiServiceClient.EventConsumer consumer = invocation.getArgument(2);
            consumer.accept(objectMapper.readTree("{\"type\":\"delta\",\"content\":\"你\"}"));
            consumer.accept(objectMapper.readTree("{\"type\":\"delta\",\"content\":\"好\"}"));
            consumer.accept(objectMapper.readTree("""
                    {"type":"usage","prompt_tokens":3,"completion_tokens":2,"total_tokens":5}
                    """));
            consumer.accept(objectMapper.readTree("{\"type\":\"done\"}"));
            return null;
        }).when(client).streamChat(eq("deepseek-chat"), any(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.stream(new ChatStreamRequest(null, "deepseek-chat", "你好"), output);

        String stream = output.toString(StandardCharsets.UTF_8);
        assertThat(stream).contains("metadata", conversationId.toString(), "\"content\":\"你\"");
        assertThat(stream).contains("\"total_tokens\":5", "\"type\":\"done\"");
        verify(repository).finishAssistantMessage(
                assistantMessageId, "你好", "completed", 3, 2, null
        );
        verify(repository).recordCompletedUsage(
                any(UUID.class), eq(1L), eq(conversationId), eq("deepseek-chat"),
                eq(3), eq(2), anyInt(), anyInt(), eq(true)
        );
    }
}
