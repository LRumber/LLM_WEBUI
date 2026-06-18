package com.lmplatform.chat.service;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.chat.config.AiServiceProperties;
import com.lmplatform.chat.model.TitleGenerationResult;
import com.lmplatform.chat.repository.ConversationRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Generates concise first-turn titles without extending the user-visible chat request. */
@Service
public class TitleGenerationService {

    private static final Logger log = LoggerFactory.getLogger(TitleGenerationService.class);
    private static final int MAX_CONTEXT_CHARS = 2_000;
    private static final int MAX_TITLE_CHARS = 20;

    private final ConversationRepository repository;
    private final AiServiceClient aiServiceClient;
    private final AiServiceProperties properties;

    public TitleGenerationService(
            ConversationRepository repository,
            AiServiceClient aiServiceClient,
            AiServiceProperties properties
    ) {
        this.repository = repository;
        this.aiServiceClient = aiServiceClient;
        this.properties = properties;
    }

    /**
     * Generates and stores a title on the dedicated executor.
     * Failures deliberately leave the prompt-derived temporary title in place.
     */
    @Async("titleTaskExecutor")
    public void generateAsync(
            UUID conversationId,
            long userId,
            String firstPrompt,
            String firstResponse
    ) {
        long startedAt = System.nanoTime();
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of(
                            "role", "system",
                            "content", "根据首轮对话生成一个不超过18个汉字的中文标题。"
                                    + "只输出标题，不加引号、句号、冒号或解释。"
                    ),
                    Map.of(
                            "role", "user",
                            "content", "用户：" + truncate(firstPrompt)
                                    + "\n助手：" + truncate(firstResponse)
                    )
            );
            TitleGenerationResult result = aiServiceClient.generateTitle(properties.titleModel(), messages);
            String title = normalize(result.title());
            if (title.isBlank()) {
                return;
            }

            // The source guard prevents a late AI task from overwriting a user-renamed title.
            if (repository.updateGeneratedTitle(conversationId, title)) {
                repository.recordTitleUsage(
                        UUID.randomUUID(),
                        userId,
                        conversationId,
                        properties.titleModel(),
                        result.inputTokens(),
                        result.outputTokens(),
                        elapsedMs(startedAt)
                );
            }
        } catch (Exception exception) {
            // Title generation is best-effort and must never fail the completed chat response.
            log.warn("Failed to generate title for conversation {}: {}", conversationId, exception.getMessage());
        }
    }

    private String truncate(String value) {
        // Bound title-generation cost independently from the main model context window.
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= MAX_CONTEXT_CHARS
                ? normalized
                : normalized.substring(0, MAX_CONTEXT_CHARS);
    }

    private String normalize(String value) {
        // Enforce the display contract even when the model ignores prompt formatting instructions.
        String title = value == null ? "" : value.strip().split("\\R", 2)[0].strip();
        title = title.replaceAll("^[\\s\\\"'“”《》]+|[\\s\\\"'“”《》。！？!?：:]+$", "");
        return title.length() <= MAX_TITLE_CHARS ? title : title.substring(0, MAX_TITLE_CHARS);
    }

    private int elapsedMs(long startedAt) {
        return (int) ((System.nanoTime() - startedAt) / 1_000_000);
    }
}
