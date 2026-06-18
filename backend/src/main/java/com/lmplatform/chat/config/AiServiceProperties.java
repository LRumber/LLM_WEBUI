package com.lmplatform.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection, timeout, and title-model settings for the Python AI gateway. */
@ConfigurationProperties(prefix = "platform.ai-service")
public record AiServiceProperties(String baseUrl, int timeoutSeconds, String titleModel) {
}
