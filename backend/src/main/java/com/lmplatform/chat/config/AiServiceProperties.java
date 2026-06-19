package com.lmplatform.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection, timeout, title-model, and bounded context settings for the Python AI gateway. */
@ConfigurationProperties(prefix = "platform.ai-service")
public record AiServiceProperties(
        String baseUrl,
        int timeoutSeconds,
        String titleModel,
        int maxContextMessages
) {
}
