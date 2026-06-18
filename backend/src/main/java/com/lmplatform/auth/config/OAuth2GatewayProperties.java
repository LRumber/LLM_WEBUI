package com.lmplatform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed configuration for the organization's OAuth2 identity gateway. */
@ConfigurationProperties(prefix = "platform.oauth2")
public record OAuth2GatewayProperties(
        String server,
        String clientId,
        String clientSecret,
        String redirectUri
) {
    /** Returns the authorization-code endpoint documented by the identity provider. */
    public String authorizeEndpoint() {
        return server + "/uias/oauth/authorize";
    }

    /** Returns the server-side authorization-code exchange endpoint. */
    public String accessTokenEndpoint() {
        return server + "/uias/oauth/access_token";
    }

    /** Returns the endpoint used to synchronize the authenticated user's profile. */
    public String userInfoEndpoint() {
        return server + "/uias/oauth/userInfo";
    }
}
