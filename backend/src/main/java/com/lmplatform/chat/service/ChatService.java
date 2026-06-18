package com.lmplatform.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.chat.model.ChatStreamRequest;
import com.lmplatform.chat.repository.ConversationRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final ConversationRepository repository;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;

    public ChatService(
            ConversationRepository repository,
            AiServiceClient aiServiceClient,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
    }

    public void stream(ChatStreamRequest request, OutputStream output) throws IOException {
        long userId = repository.ensureDevelopmentUser();
        boolean newConversation = request.conversationId() == null;
        UUID conversationId = newConversation
                ? repository.createConversation(userId, request.model(), request.content())
                : request.conversationId();
        if (!newConversation && !repository.belongsToUser(conversationId, userId)) {
            writeEvent(output, Map.of("type", "error", "message", "Conversation not found"));
            return;
        }

        repository.appendMessage(conversationId, "user", request.content(), "completed");
        UUID assistantMessageId = repository.appendMessage(
                conversationId, "assistant", "", "generating"
        );
        writeEvent(output, Map.of(
                "type", "metadata",
                "conversation_id", conversationId,
                "message_id", assistantMessageId
        ));

        StringBuilder assistantContent = new StringBuilder();
        AtomicInteger inputTokens = new AtomicInteger();
        AtomicInteger outputTokens = new AtomicInteger();
        AtomicLong firstTokenAt = new AtomicLong();
        long startedAt = System.nanoTime();

        try {
            aiServiceClient.streamChat(
                    request.model(),
                    repository.modelMessages(conversationId),
                    event -> handleAiEvent(event, output, assistantContent, inputTokens, outputTokens, firstTokenAt)
            );
            int durationMs = elapsedMs(startedAt);
            Integer firstTokenMs = firstTokenAt.get() == 0
                    ? null
                    : (int) ((firstTokenAt.get() - startedAt) / 1_000_000);
            repository.finishAssistantMessage(
                    assistantMessageId,
                    assistantContent.toString(),
                    "completed",
                    inputTokens.get(),
                    outputTokens.get(),
                    null
            );
            repository.recordCompletedUsage(
                    UUID.randomUUID(),
                    userId,
                    conversationId,
                    request.model(),
                    inputTokens.get(),
                    outputTokens.get(),
                    firstTokenMs,
                    durationMs,
                    newConversation
            );
        } catch (IOException exception) {
            String status = assistantContent.isEmpty() ? "failed" : "interrupted";
            repository.finishAssistantMessage(
                    assistantMessageId, assistantContent.toString(), status,
                    inputTokens.get(), outputTokens.get(), "stream_error"
            );
            try {
                writeEvent(output, Map.of("type", "error", "message", safeMessage(exception)));
            } catch (IOException clientDisconnected) {
                // The partial response has already been persisted.
            }
        }
    }

    private void handleAiEvent(
            JsonNode event,
            OutputStream output,
            StringBuilder assistantContent,
            AtomicInteger inputTokens,
            AtomicInteger outputTokens,
            AtomicLong firstTokenAt
    ) throws IOException {
        String type = event.path("type").asText();
        if ("delta".equals(type)) {
            String content = event.path("content").asText();
            if (!content.isEmpty()) {
                firstTokenAt.compareAndSet(0, System.nanoTime());
                assistantContent.append(content);
            }
        } else if ("usage".equals(type)) {
            inputTokens.set(event.path("prompt_tokens").asInt());
            outputTokens.set(event.path("completion_tokens").asInt());
        } else if ("error".equals(type)) {
            throw new IOException(event.path("message").asText("AI service failed"));
        }
        writeEvent(output, objectMapper.convertValue(event, Map.class));
    }

    private void writeEvent(OutputStream output, Map<String, ?> event) throws IOException {
        String payload = "data: " + objectMapper.writeValueAsString(event) + "\n\n";
        output.write(payload.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private int elapsedMs(long startedAt) {
        return (int) ((System.nanoTime() - startedAt) / 1_000_000);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Chat service failed" : message;
    }
}
