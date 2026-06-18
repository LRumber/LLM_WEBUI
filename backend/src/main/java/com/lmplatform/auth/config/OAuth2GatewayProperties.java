package com.lmplatform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.oauth2")
public record OAuth2GatewayProperties(
        String server,
        String clientId,
        String clientSecret,
        String redirectUri
) {
    public String authorizeEndpoint() {
        return server + "/uias/oauth/authorize";
    }

    public String accessTokenEndpoint() {
        return server + "/uias/oauth/access_token";
    }

    public String userInfoEndpoint() {
        return server + "/uias/oauth/userInfo";
    }
}
