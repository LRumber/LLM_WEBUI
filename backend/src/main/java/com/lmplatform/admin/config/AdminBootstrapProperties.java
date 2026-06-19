package com.lmplatform.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional first local administrator supplied through deployment secrets. */
@ConfigurationProperties(prefix = "platform.admin.bootstrap")
public record AdminBootstrapProperties(String username, String password, String displayName) {
}
