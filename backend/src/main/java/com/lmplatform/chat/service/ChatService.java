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

    private static final int COMPACTION_FETCH_LIMIT = 200;
    private static final int RECENT_MESSAGES_AFTER_SUMMARY = 12;
    private static final int SUMMARY_SOURCE_MAX_CHARS = 48_000;

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
    public void stream(long userId, ChatStreamRequest request, OutputStream output) throws IOException {
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
        String modelContent = request.modelContent() == null || request.modelContent().isBlank()
                ? request.content()
                : request.modelContent();
        streamAssistant(
                userId, conversationId, assistantMessageId, request.model(), newConversation,
                newConversation ? request.content() : null, request.thinkingEnabled(), citations,
                modelContent, output
        );
    }

    /** Supersedes an owned assistant answer and streams a replacement from the same prior context. */
    public void regenerate(
            long userId,
            UUID conversationId,
            UUID assistantMessageId,
            String model,
            OutputStream output
    ) throws IOException {
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
        streamAssistant(userId, conversationId, replacementId, model, false, null, false, citations, null, output);
    }

    /** Edits a user turn, hides following stale messages, then streams a fresh answer. */
    public void editAndRegenerate(
            long userId,
            UUID conversationId,
            UUID userMessageId,
            String content,
            String model,
            boolean thinkingEnabled,
            OutputStream output
    ) throws IOException {
        ConversationRepository.MessageVersion replacementVersion = repository.replaceUserMessageForRegeneration(
                conversationId, userMessageId, userId, content
        );
        if (replacementVersion == null) {
            writeEvent(output, Map.of("type", "error", "message", "User message not found"));
            return;
        }
        writeEvent(output, Map.of(
                "type", "edit_metadata",
                "message_id", replacementVersion.messageId(),
                "version_index", replacementVersion.versionIndex()
        ));
        UUID assistantMessageId = repository.appendVersionedMessage(
                conversationId, "assistant", "", "generating",
                replacementVersion.groupId(), replacementVersion.versionIndex()
        );
        List<KnowledgeSearchResult> citations = retrieveKnowledge(conversationId, userId, content);
        streamAssistant(
                userId, conversationId, assistantMessageId, model, false,
                null, thinkingEnabled, citations, null, output
        );
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
            String currentTurnModelContent,
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
                    buildModelMessages(conversationId, model, citations, currentTurnModelContent, output),
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
            UUID conversationId,
            String model,
            List<KnowledgeSearchResult> citations,
            String currentTurnModelContent,
            OutputStream output
    ) throws IOException {
        ConversationRepository.ContextSummary summary = repository.contextSummary(conversationId);
        int summarizedUntil = summary == null ? 0 : summary.untilSequence();
        String summaryText = summary == null ? "" : nullToEmpty(summary.content()).strip();
        List<ModelContextMessage> contextMessages = repository.modelContextMessagesAfter(
                conversationId, summarizedUntil, COMPACTION_FETCH_LIMIT
        );
        if (contextMessages == null) {
            contextMessages = repository.modelContextMessages(conversationId, maxContextMessages);
        }
        List<Map<String, ?>> result = renderModelMessages(contextMessages, currentTurnModelContent, summaryText);
        result = insertKnowledgeContext(result, citations);

        Integer contextWindow = modelContextWindow(model);
        if (contextWindow == null || withinBudget(result, contextWindow)) {
            return result;
        }

        writeEvent(output, Map.of(
                "type", "context_compaction",
                "status", "started",
                "message", "正在压缩上下文，请稍等"
        ));
        CompactedContext compacted = compactContext(
                conversationId, model, summaryText, contextMessages, currentTurnModelContent, contextWindow
        );
        writeEvent(output, Map.of(
                "type", "context_compaction",
                "status", "completed",
                "message", "上下文压缩完成，正在生成回复"
        ));
        result = renderModelMessages(compacted.messages(), currentTurnModelContent, compacted.summary());
        result = insertKnowledgeContext(result, citations);
        return trimToBudget(result, contextWindow);
    }

    private List<Map<String, ?>> renderModelMessages(
            List<ModelContextMessage> messages, String currentTurnModelContent, String summary
    ) throws IOException {
        List<Map<String, ?>> result = new ArrayList<>();
        int lastUserIndex = -1;
        for (ModelContextMessage message : messages) {
            int currentIndex = result.size();
            if ("user".equals(message.role())) {
                lastUserIndex = currentIndex;
            }
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
        if (currentTurnModelContent != null && !currentTurnModelContent.isBlank() && lastUserIndex >= 0) {
            result.set(lastUserIndex, replaceMessageContent(result.get(lastUserIndex), currentTurnModelContent));
        }
        if (summary != null && !summary.isBlank()) {
            result.add(firstNonSystemIndex(result), Map.of(
                    "role", "system",
                    "content", "以下是本轮对话较早内容的压缩摘要，请作为上下文参考，不要向用户说明存在该摘要。\n\n" + summary
            ));
        }
        return result;
    }

    private List<Map<String, ?>> insertKnowledgeContext(
            List<Map<String, ?>> messages, List<KnowledgeSearchResult> citations
    ) {
        List<Map<String, ?>> result = new ArrayList<>(messages);
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
            result.add(firstNonSystemIndex(result), Map.of("role", "system", "content", context.toString()));
        }
        return result;
    }

    private CompactedContext compactContext(
            UUID conversationId,
            String model,
            String existingSummary,
            List<ModelContextMessage> contextMessages,
            String currentTurnModelContent,
            int contextWindow
    ) {
        List<ModelContextMessage> dialogue = contextMessages.stream()
                .filter(message -> !"system".equals(message.role()))
                .toList();
        if (dialogue.size() <= RECENT_MESSAGES_AFTER_SUMMARY) {
            return new CompactedContext(existingSummary, keepRecentWithSystemMessages(contextMessages, 8));
        }
        int splitIndex = Math.max(1, dialogue.size() - RECENT_MESSAGES_AFTER_SUMMARY);
        List<ModelContextMessage> older = dialogue.subList(0, splitIndex);
        List<ModelContextMessage> recentDialogue = dialogue.subList(splitIndex, dialogue.size());
        int untilSequence = older.getLast().sequenceNo();
        String nextSummary = summarizeOlderContext(model, existingSummary, older, contextWindow);
        if (nextSummary.isBlank()) {
            return new CompactedContext(existingSummary, mergeSystemAndRecent(contextMessages, recentDialogue));
        }
        repository.saveContextSummary(conversationId, nextSummary, untilSequence);
        return new CompactedContext(nextSummary, mergeSystemAndRecent(contextMessages, recentDialogue));
    }

    private String summarizeOlderContext(
            String model, String existingSummary, List<ModelContextMessage> older, int contextWindow
    ) {
        String source = boundedSummarySource(existingSummary, older, contextWindow);
        if (source.isBlank()) {
            return existingSummary;
        }
        List<Map<String, String>> messages = List.of(
                Map.of(
                        "role", "system",
                        "content", """
                                你负责为企业智能工作台压缩长对话上下文。请保留用户目标、关键事实、已确认结论、待办事项、约束条件、重要代码/配置名和未解决问题。\
                                删除寒暄和重复内容。输出一段结构清晰的中文摘要，不要编造不存在的信息。
                                """
                ),
                Map.of(
                        "role", "user",
                        "content", "请将以下历史对话压缩为后续回答可用的上下文摘要：\n\n" + source
                )
        );
        try {
            return aiServiceClient.generateTitle(model, messages).title().strip();
        } catch (IOException | RuntimeException exception) {
            return existingSummary;
        }
    }

    private String boundedSummarySource(
            String existingSummary, List<ModelContextMessage> older, int contextWindow
    ) {
        int maxChars = Math.min(SUMMARY_SOURCE_MAX_CHARS, Math.max(4_000, contextWindow));
        StringBuilder source = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            source.append("【已有摘要】\n").append(existingSummary.strip()).append("\n\n");
        }
        source.append("【新增历史消息】\n");
        for (ModelContextMessage message : older) {
            source.append(roleLabel(message.role())).append(": ")
                    .append(message.content().strip()).append('\n');
            if (!message.attachments().isEmpty()) {
                source.append("[图片附件已省略，仅保留文字上下文]\n");
            }
            source.append('\n');
            if (source.length() >= maxChars) {
                return source.substring(source.length() - maxChars);
            }
        }
        return source.toString();
    }

    private List<ModelContextMessage> keepRecentWithSystemMessages(
            List<ModelContextMessage> messages, int recentDialogueLimit
    ) {
        List<ModelContextMessage> dialogue = messages.stream()
                .filter(message -> !"system".equals(message.role()))
                .toList();
        int start = Math.max(0, dialogue.size() - recentDialogueLimit);
        return mergeSystemAndRecent(messages, dialogue.subList(start, dialogue.size()));
    }

    private List<ModelContextMessage> mergeSystemAndRecent(
            List<ModelContextMessage> messages, List<ModelContextMessage> recentDialogue
    ) {
        List<ModelContextMessage> merged = new ArrayList<>();
        messages.stream()
                .filter(message -> "system".equals(message.role()))
                .forEach(merged::add);
        merged.addAll(recentDialogue);
        merged.sort(java.util.Comparator.comparingInt(ModelContextMessage::sequenceNo));
        return merged;
    }

    private List<Map<String, ?>> trimToBudget(List<Map<String, ?>> messages, int contextWindow) {
        List<Map<String, ?>> trimmed = new ArrayList<>(messages);
        while (!withinBudget(trimmed, contextWindow)) {
            int removeIndex = firstRemovableMessageIndex(trimmed);
            if (removeIndex < 0) {
                break;
            }
            trimmed.remove(removeIndex);
        }
        return trimmed;
    }

    private int firstRemovableMessageIndex(List<Map<String, ?>> messages) {
        for (int index = 0; index < messages.size() - 2; index++) {
            if (!"system".equals(String.valueOf(messages.get(index).get("role")))) {
                return index;
            }
        }
        return -1;
    }

    private boolean withinBudget(List<Map<String, ?>> messages, int contextWindow) {
        int usablePromptTokens = Math.max(1, (int) (contextWindow * 0.8) - 512);
        return estimateTokens(messages) <= usablePromptTokens;
    }

    private int estimateTokens(List<Map<String, ?>> messages) {
        int total = 0;
        for (Map<String, ?> message : messages) {
            total += 4;
            Object content = message.get("content");
            if (content instanceof String text) {
                total += estimateTextTokens(text);
            } else if (content instanceof List<?> parts) {
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> map && "text".equals(String.valueOf(map.get("type")))) {
                        total += estimateTextTokens(String.valueOf(map.get("text")));
                    } else if (part instanceof Map<?, ?> map && "image_url".equals(String.valueOf(map.get("type")))) {
                        total += 1024;
                    }
                }
            }
        }
        return total + 16;
    }

    private int estimateTextTokens(String text) {
        return Math.max(1, (nullToEmpty(text).length() + 1) / 2);
    }

    private Integer modelContextWindow(String model) {
        try {
            JsonNode modelInfo = aiServiceClient.getModel(model);
            if (modelInfo != null && modelInfo.path("context_window").isNumber()) {
                return modelInfo.path("context_window").asInt();
            }
        } catch (RuntimeException ignored) {
            // Missing metadata should not block chat; the AI service still enforces its own model limit.
        }
        return null;
    }

    private int firstNonSystemIndex(List<Map<String, ?>> messages) {
        int index = 0;
        while (index < messages.size()
                && "system".equals(String.valueOf(messages.get(index).get("role")))) {
            index++;
        }
        return index;
    }

    private String roleLabel(String role) {
        return switch (role) {
            case "system" -> "系统";
            case "assistant" -> "助手";
            default -> "用户";
        };
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, ?> replaceMessageContent(Map<String, ?> message, String content) {
        Object existing = message.get("content");
        if (!(existing instanceof List<?> parts)) {
            return Map.of("role", message.get("role"), "content", content);
        }
        List<Map<String, ?>> replaced = new ArrayList<>();
        boolean textReplaced = false;
        for (Object part : parts) {
            if (part instanceof Map<?, ?> map && "text".equals(String.valueOf(map.get("type"))) && !textReplaced) {
                replaced.add(Map.of("type", "text", "text", content));
                textReplaced = true;
            } else if (part instanceof Map<?, ?> map) {
                replaced.add((Map<String, ?>) map);
            }
        }
        if (!textReplaced) {
            replaced.add(0, Map.of("type", "text", "text", content));
        }
        return Map.of("role", message.get("role"), "content", replaced);
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

    private record CompactedContext(String summary, List<ModelContextMessage> messages) {
    }
}
