package com.lmplatform.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.chat.config.AiServiceProperties;
import com.lmplatform.chat.model.ChatStreamRequest;
import com.lmplatform.chat.model.ModelContextMessage;
import com.lmplatform.chat.repository.ConversationRepository;
import com.lmplatform.file.service.ObjectStorageService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/** Coordinates persistence, model streaming, SSE forwarding, and usage accounting. */
@Service
public class ChatService {

    private final ConversationRepository repository;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;
    private final TitleGenerationService titleGenerationService;
    private final int maxContextMessages;
    private final ObjectStorageService objectStorageService;

    public ChatService(
            ConversationRepository repository,
            AiServiceClient aiServiceClient,
            ObjectMapper objectMapper,
            TitleGenerationService titleGenerationService,
            AiServiceProperties properties,
            ObjectStorageService objectStorageService
    ) {
        this.repository = repository;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
        this.titleGenerationService = titleGenerationService;
        this.maxContextMessages = Math.max(2, properties.maxContextMessages());
        this.objectStorageService = objectStorageService;
    }

    /**
     * Executes one chat turn and writes normalized SSE events directly to the servlet response.
     * Partial assistant output is persisted when the upstream or browser connection fails.
     */
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

        if (newConversation) {
            // Persist the selected system instruction so every future context reconstruction is deterministic.
            repository.findPromptTemplate(request.templateId(), userId).ifPresent(template ->
                    repository.appendMessage(conversationId, "system", template, "completed")
            );
        }
        UUID userMessageId = repository.appendMessage(
                conversationId, "user", request.content(), "completed"
        );
        repository.attachFiles(userMessageId, request.attachmentIds(), userId);
        UUID assistantMessageId = repository.appendMessage(
                conversationId, "assistant", "", "generating"
        );
        streamAssistant(
                userId, conversationId, assistantMessageId, request.model(), newConversation,
                newConversation ? request.content() : null, output
        );
    }

    /** Supersedes an owned assistant answer and streams a replacement from the same prior context. */
    public void regenerate(
            UUID conversationId,
            UUID assistantMessageId,
            String model,
            OutputStream output
    ) throws IOException {
        long userId = repository.ensureDevelopmentUser();
        if (!repository.prepareAssistantRegeneration(
                conversationId, assistantMessageId, userId
        )) {
            writeEvent(output, Map.of("type", "error", "message", "Assistant message not found"));
            return;
        }
        UUID replacementId = repository.appendMessage(conversationId, "assistant", "", "generating");
        streamAssistant(userId, conversationId, replacementId, model, false, null, output);
    }

    /** Runs the shared generation lifecycle for new turns and regenerated answers. */
    private void streamAssistant(
            long userId,
            UUID conversationId,
            UUID assistantMessageId,
            String model,
            boolean newConversation,
            String firstPrompt,
            OutputStream output
    ) throws IOException {
        writeEvent(output, Map.of(
                "type", "metadata", "conversation_id", conversationId, "message_id", assistantMessageId
        ));

        StringBuilder assistantContent = new StringBuilder();
        AtomicInteger inputTokens = new AtomicInteger();
        AtomicInteger outputTokens = new AtomicInteger();
        AtomicLong firstTokenAt = new AtomicLong();
        long startedAt = System.nanoTime();

        try {
            aiServiceClient.streamChat(
                    model,
                    buildModelMessages(conversationId),
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
                    model,
                    inputTokens.get(),
                    outputTokens.get(),
                    firstTokenMs,
                    durationMs,
                    newConversation
            );
            if (newConversation) {
                // Run after persistence and off the request thread so title generation never delays SSE completion.
                titleGenerationService.generateAsync(
                        conversationId,
                        userId,
                        firstPrompt,
                        assistantContent.toString()
                );
            }
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

    /** Builds text or OpenAI-compatible image content without exposing MinIO outside the core service. */
    private List<Map<String, ?>> buildModelMessages(UUID conversationId) throws IOException {
        List<Map<String, ?>> result = new ArrayList<>();
        for (ModelContextMessage message : repository.modelContextMessages(
                conversationId, maxContextMessages
        )) {
            if (message.attachments().isEmpty()) {
                result.add(Map.of("role", message.role(), "content", message.content()));
                continue;
            }
            List<Map<String, ?>> parts = new ArrayList<>();
            if (!message.content().isBlank()) {
                parts.add(Map.of("type", "text", "text", message.content()));
            }
            for (var attachment : message.attachments()) {
                try {
                    byte[] bytes = objectStorageService.get(attachment.objectKey(), 20 * 1024 * 1024);
                    String dataUrl = "data:" + attachment.mediaType() + ";base64,"
                            + Base64.getEncoder().encodeToString(bytes);
                    parts.add(Map.of(
                            "type", "image_url",
                            "image_url", Map.of("url", dataUrl, "detail", "auto")
                    ));
                } catch (Exception exception) {
                    throw new IOException("Failed to load multimodal image", exception);
                }
            }
            result.add(Map.of("role", message.role(), "content", parts));
        }
        return result;
    }

    private void handleAiEvent(
            JsonNode event,
            OutputStream output,
            StringBuilder assistantContent,
            AtomicInteger inputTokens,
            AtomicInteger outputTokens,
            AtomicLong firstTokenAt
    ) throws IOException {
        // Keep accounting state beside the stream while forwarding the original normalized event.
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
        // A flush per event is required for visible token-by-token rendering in the browser.
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
