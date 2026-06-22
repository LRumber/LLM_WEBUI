package com.lmplatform.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.lmplatform.auth.config.OAuth2GatewayProperties;
import com.lmplatform.auth.model.AuthenticatedShadowUser;
import com.lmplatform.auth.repository.ShadowUserRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Executes the organization-specific authorization-code exchange and shadow-user synchronization. */
@Service
public class OAuth2LoginService {

    private final OAuth2GatewayProperties properties;
    private final ShadowUserRepository repository;
    private final RestClient restClient;

    public OAuth2LoginService(
            OAuth2GatewayProperties properties,
            ShadowUserRepository repository
    ) {
        this.properties = properties;
        this.repository = repository;
        this.restClient = RestClient.create();
    }

    public AuthenticatedShadowUser authenticate(String code) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri());
        JsonNode tokenResponse = restClient.post()
                .uri(properties.accessTokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        String accessToken = text(tokenResponse, "access_token", "accessToken", "token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("OAuth2 token response did not contain an access token");
        }
        JsonNode response = restClient.get()
                .uri(properties.userInfoEndpoint())
                .headers(headers -> headers.setBearerAuth(accessToken))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        JsonNode user = response != null && response.path("data").isObject()
                ? response.path("data") : response;
        long externalUserId = longValue(user, "userId", "id");
        String account = firstNonBlank(
                text(user, "accountNumber", "account", "username", "loginName"),
                String.valueOf(externalUserId)
        );
        String displayName = firstNonBlank(
                text(user, "displayName", "name", "realName"), account
        );
        short status = (short) intValue(user, 1, "status");
        return repository.upsert(
                externalUserId, account, displayName,
                text(user, "userNumber", "userNo", "employeeNumber"),
                nullableLong(user, "tenantId"), text(user, "email"),
                text(user, "telephone", "phone", "mobile"),
                text(user, "avatarPath", "avatar"), status
        );
    }

    private String text(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private long longValue(JsonNode node, String... names) {
        Long value = nullableLong(node, names);
        if (value == null) throw new IllegalStateException("OAuth2 userInfo did not contain userId");
        return value;
    }

    private Long nullableLong(JsonNode node, String... names) {
        String value = text(node, names);
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("OAuth2 numeric identity field was invalid", exception);
        }
    }

    private int intValue(JsonNode node, int fallback, String... names) {
        String value = text(node, names);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
