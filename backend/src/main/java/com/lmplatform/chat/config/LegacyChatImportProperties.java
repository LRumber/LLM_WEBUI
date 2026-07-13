package com.lmplatform.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.legacy-chat-import")
public record LegacyChatImportProperties(
        boolean enabled,
        String dataRoot,
        String defaultModel
) {
    public String resolvedDefaultModel() {
        return defaultModel == null || defaultModel.isBlank() ? "legacy-import" : defaultModel.strip();
    }
}
