package com.lmplatform.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.lmplatform.auth.config.OAuth2GatewayProperties;
import com.lmplatform.auth.model.AuthenticatedShadowUser;
import com.lmplatform.auth.repository.ShadowUserRepository;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

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
        String tokenUri = UriComponentsBuilder.fromUriString(properties.accessTokenEndpoint())
                .queryParam("client_id", properties.clientId())
                .queryParam("client_secret", properties.clientSecret())
                .queryParam("code", code)
                .build()
                .encode()
                .toUriString();
        JsonNode tokenResponse = restClient.method(HttpMethod.POST)
                .uri(tokenUri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        String accessToken = text(tokenResponse, "access_token", "accessToken", "token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("OAuth2 token response did not contain an access token");
        }
        String userInfoUri = UriComponentsBuilder.fromUriString(properties.userInfoEndpoint())
                .queryParam("access_token", accessToken)
                .build()
                .encode()
                .toUriString();
        JsonNode response = restClient.get()
                .uri(userInfoUri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        JsonNode user = response != null && response.path("data").isObject()
                ? response.path("data") : response;
        long externalUserId = longValue(user, "userId", "id");
        String idNo = text(user, "idNo", "identityNo", "employeeId");
        String account = firstNonBlank(text(user, "accountNumber"), String.valueOf(externalUserId));
        String displayName = firstNonBlank(
                text(user, "userName", "displayName", "name", "realName"), account
        );
        short status = (short) intValue(user, 1, "userStatus", "isUsing", "status");
        return repository.upsert(
                externalUserId, idNo, account, displayName,
                text(user, "userNumber"),
                nullableLong(user, "tenantId"), nullableShort(user, "idType"),
                null, text(user, "email"),
                text(user, "tel", "telephone", "phone", "mobile"),
                text(user, "imgPath", "avatarPath", "avatar"), status
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

    private Short nullableShort(JsonNode node, String... names) {
        String value = text(node, names);
        if (value == null) return null;
        try {
            return Short.parseShort(value);
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
