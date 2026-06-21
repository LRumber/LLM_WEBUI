package com.lmplatform.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.chat.config.AiServiceProperties;
import com.lmplatform.chat.model.ChatStreamRequest;
import com.lmplatform.chat.model.ModelContextMessage;
import com.lmplatform.chat.model.PromptTemplate;
import com.lmplatform.chat.repository.ConversationRepository;
import com.lmplatform.file.service.ObjectStorageService;
import com.lmplatform.knowledge.model.KnowledgeSearchResult;
import com.lmplatform.knowledge.service.KnowledgeBaseService;
import com.lmplatform.knowledge.service.KnowledgeSearchService;
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
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeSearchService knowledgeSearchService;

    public ChatService(
            ConversationRepository repository,
            AiServiceClient aiServiceClient,
            ObjectMapper objectMapper,
            TitleGenerationService titleGenerationService,
            AiServiceProperties properties,
            ObjectStorageService objectStorageService,
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeSearchService knowledgeSearchService
    ) {
        this.repository = repository;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
        this.titleGenerationService = titleGenerationService;
        this.maxContextMessages = Math.max(2, properties.maxContextMessages());
        this.objectStorageService = objectStorageService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeSearchService = knowledgeSearchService;
    }

    /**
     * Executes one chat turn and writes normalized SSE events directly to the servlet response.
     * Partial assistant output is persisted when the upstream or browser connection fails.
     */
    public void stream(ChatStreamRequest request, OutputStream output) throws IOException {
        long userId = repository.ensureDevelopmentUser();
        boolean newConversation = request.conversationId() == null;
        PromptTemplate selectedTemplate = newConversation
                ? repository.findPromptTemplate(request.templateId(), userId).orElse(null)
                : null;
        if (newConversation && request.knowledgeBaseIds() != null) {
            // Validate every selection before creating conversation state or calling retrieval.
            request.knowledgeBaseIds().stream().distinct()
                    .forEach(id -> knowledgeBaseService.find(id, userId));
        }
        UUID conversationId = newConversation
                ? repository.createConversation(
                        userId, request.model(), request.content(),
                        selectedTemplate == null ? null : selectedTemplate.id(),
                        selectedTemplate == null ? null : selectedTemplate.name()
                )
                : request.conversationId();
        if (!newConversation && !repository.belongsToUser(conversationId, userId)) {
            writeEvent(output, Map.of("type", "error", "message", "Conversation not found"));
            return;
        }

        if (newConversation) {
            repository.bindKnowledgeBases(conversationId, userId, request.knowledgeBaseIds());
            // Persist the selected system instruction so every future context reconstruction is deterministic.
            if (selectedTemplate != null) {
                repository.appendMessage(
                        conversationId, "system", selectedTemplate.systemPrompt(), "completed"
                );
            }
        }
        UUID userMessageId = repository.appendMessage(
                conversationId, "user", request.content(), "completed"
        );
        repository.attachFiles(userMessageId, request.attachmentIds(), userId);
        UUID assistantMessageId = repository.appendMessage(
                conversationId, "assistant", "", "generating"
        );
        List<KnowledgeSearchResult> citations = retrieveKnowledge(
                conversationId, userId, request.content()
        );
        streamAssistant(
                userId, conversationId, assistantMessageId, request.model(), newConversation,
                newConversation ? request.content() : null, request.thinkingEnabled(), citations, output
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
        List<KnowledgeSearchResult> citations = retrieveKnowledge(
                conversationId, userId, repository.latestUserContent(conversationId)
        );
        streamAssistant(userId, conversationId, replacementId, model, false, null, false, citations, output);
    }

    /** Runs the shared generation lifecycle for new turns and regenerated answers. */
    private void streamAssistant(
            long userId,
            UUID conversationId,
            UUID assistantMessageId,
            String model,
            boolean newConversation,
            String firstPrompt,
            boolean thinkingEnabled,
            List<KnowledgeSearchResult> citations,
            OutputStream output
    ) throws IOException {
        writeEvent(output, Map.of(
                "type", "metadata", "conversation_id", conversationId, "message_id", assistantMessageId
        ));
        if (!citations.isEmpty()) {
            writeEvent(output, Map.of("type", "citations", "citations", citations));
        }

        StringBuilder assistantContent = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        AtomicInteger inputTokens = new AtomicInteger();
        AtomicInteger outputTokens = new AtomicInteger();
        AtomicInteger reasoningDurationMs = new AtomicInteger();
        AtomicLong firstTokenAt = new AtomicLong();
        AtomicLong reasoningStartedAt = new AtomicLong();
        long startedAt = System.nanoTime();

        try {
            aiServiceClient.streamChat(
                    model,
                    buildModelMessages(conversationId, citations),
                    thinkingEnabled,
                    event -> handleAiEvent(
                            event, output, assistantContent, reasoningContent,
                            inputTokens, outputTokens, firstTokenAt, reasoningStartedAt,
                            reasoningDurationMs, startedAt
                    )
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
                    reasoningContent.isEmpty() ? null : reasoningContent.toString(),
                    reasoningDurationMs.get() == 0 ? null : reasoningDurationMs.get(),
                    null
            );
            repository.saveCitations(assistantMessageId, citations);
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
                    inputTokens.get(), outputTokens.get(),
                    reasoningContent.isEmpty() ? null : reasoningContent.toString(),
                    reasoningDurationMs.get() == 0 ? null : reasoningDurationMs.get(),
                    "stream_error"
            );
            repository.saveCitations(assistantMessageId, citations);
            try {
                writeEvent(output, Map.of("type", "error", "message", safeMessage(exception)));
            } catch (IOException clientDisconnected) {
                // The partial response has already been persisted.
            }
        }
    }

    /** Builds text or OpenAI-compatible image content without exposing MinIO outside the core service. */
    private List<Map<String, ?>> buildModelMessages(
            UUID conversationId, List<KnowledgeSearchResult> citations
    ) throws IOException {
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
        if (!citations.isEmpty()) {
            StringBuilder context = new StringBuilder("""
                    以下是经过权限校验的知识库检索结果。请优先依据这些内容回答；引用时使用 [1]、[2] 格式。如果材料不足，请明确说明未找到可靠依据。\n\n
                    """);
            for (int index = 0; index < citations.size(); index++) {
                KnowledgeSearchResult citation = citations.get(index);
                context.append('[').append(index + 1).append("] ")
                        .append(citation.filename());
                if (citation.pageNumber() != null) {
                    context.append(" 第").append(citation.pageNumber()).append("页");
                }
                context.append('\n').append(citation.content()).append("\n\n");
            }
            int firstUser = 0;
            while (firstUser < result.size()
                    && !"user".equals(String.valueOf(result.get(firstUser).get("role")))) {
                firstUser++;
            }
            result.add(firstUser, Map.of("role", "system", "content", context.toString()));
        }
        return result;
    }

    /** Retrieves from every immutable conversation binding and keeps the strongest unique chunks. */
    private List<KnowledgeSearchResult> retrieveKnowledge(
            UUID conversationId, long userId, String query
    ) {
        if (query == null || query.isBlank()) return List.of();
        Map<UUID, KnowledgeSearchResult> unique = new java.util.HashMap<>();
        for (var knowledgeBase : repository.conversationKnowledgeBases(conversationId, userId)) {
            for (KnowledgeSearchResult result : knowledgeSearchService.search(
                    knowledgeBase.id(), userId, query, 4
            )) {
                unique.merge(result.chunkId(), result, (left, right) ->
                        left.score() >= right.score() ? left : right);
            }
        }
        return unique.values().stream()
                .filter(result -> result.score() >= 0.1)
                .sorted(java.util.Comparator.comparingDouble(KnowledgeSearchResult::score).reversed())
                .limit(8)
                .toList();
    }

    private void handleAiEvent(
            JsonNode event,
            OutputStream output,
            StringBuilder assistantContent,
            StringBuilder reasoningContent,
            AtomicInteger inputTokens,
            AtomicInteger outputTokens,
            AtomicLong firstTokenAt,
            AtomicLong reasoningStartedAt,
            AtomicInteger reasoningDurationMs,
            long requestStartedAt
    ) throws IOException {
        // Keep accounting state beside the stream while forwarding the original normalized event.
        String type = event.path("type").asText();
        if ("reasoning_delta".equals(type)) {
            String content = event.path("content").asText();
            if (!content.isEmpty()) {
                reasoningStartedAt.compareAndSet(0, requestStartedAt);
                reasoningContent.append(content);
            }
        } else if ("delta".equals(type)) {
            finishReasoning(output, reasoningStartedAt, reasoningDurationMs);
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
        } else if ("done".equals(type)) {
            finishReasoning(output, reasoningStartedAt, reasoningDurationMs);
        }
        writeEvent(output, objectMapper.convertValue(event, Map.class));
    }

    /** Emits one stable completion marker when reasoning transitions into the final answer. */
    private void finishReasoning(
            OutputStream output,
            AtomicLong reasoningStartedAt,
            AtomicInteger reasoningDurationMs
    ) throws IOException {
        if (reasoningStartedAt.get() == 0 || reasoningDurationMs.get() != 0) {
            return;
        }
        int duration = (int) ((System.nanoTime() - reasoningStartedAt.get()) / 1_000_000);
        reasoningDurationMs.compareAndSet(0, Math.max(1, duration));
        writeEvent(output, Map.of(
                "type", "reasoning_done", "duration_ms", reasoningDurationMs.get()
        ));
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
