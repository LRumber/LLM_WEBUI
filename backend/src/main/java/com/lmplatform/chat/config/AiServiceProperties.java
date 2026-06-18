package com.lmplatform.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.ai-service")
public record AiServiceProperties(String baseUrl, int timeoutSeconds, String titleModel) {
}
