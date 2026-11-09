package com.lmplatform.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.api.model.ApiDeveloperSummary;
import com.lmplatform.api.model.ApiKeyCreateResponse;
import com.lmplatform.api.model.ApiKeySummary;
import com.lmplatform.api.model.ApiModelSummary;
import com.lmplatform.api.model.ApiPrincipal;
import com.lmplatform.api.repository.ApiGatewayRepository;
import com.lmplatform.chat.client.AiServiceClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ApiGatewayService {

    private final ApiGatewayRepository repository;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;
    private final String publicBaseUrl;

    public ApiGatewayService(
            ApiGatewayRepository repository,
            AiServiceClient aiServiceClient,
            ObjectMapper objectMapper,
            @Value("${PUBLIC_BASE_URL:http://localhost:8505}") String publicBaseUrl
    ) {
        this.repository = repository;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
        this.publicBaseUrl = publicBaseUrl;
    }

    public ApiDeveloperSummary developerSummary(long userId) {
        return new ApiDeveloperSummary(
                baseUrl(),
                repository.quota(userId),
                publicModels(),
                repository.dailyUsage(userId, 30)
        );
    }

    public List<ApiKeySummary> keys(long userId) {
        return repository.keysForUser(userId);
    }

    public ApiKeyCreateResponse createKey(long userId, String name) {
        var created = repository.createKey(userId, name);
        return new ApiKeyCreateResponse(created.key(), created.secret());
    }

    public ApiKeyCreateResponse resetKey(long userId, UUID keyId) {
        var created = repository.resetKey(userId, keyId);
        return new ApiKeyCreateResponse(created.key(), created.secret());
    }

    public ApiPrincipal authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiGatewayException("invalid_api_key", "Missing API key", 401);
        }
        String secret = authorization.substring("Bearer ".length()).trim();
        return repository.authenticate(secret)
                .orElseThrow(() -> new ApiGatewayException("invalid_api_key", "Invalid or disabled API key", 401));
    }

    public void ensureQuota(ApiPrincipal principal) {
        if (!principal.unlimited()
                && (principal.totalQuota() <= 0 || principal.usedTokens() >= principal.totalQuota())) {
            throw new ApiGatewayException(
                    "quota_exceeded",
                    "API token quota exceeded. Please contact your administrator.",
                    429
            );
        }
    }

    public void ensureModelEnabled(String model) {
        if (model == null || model.isBlank()) {
            throw new ApiGatewayException("invalid_request", "Missing model", 400);
        }
        if (!repository.isModelEnabled(model)) {
            throw new ApiGatewayException("model_not_allowed", "Model is not enabled for API access", 403);
        }
    }

    public List<ApiModelSummary> allModelsForAdmin() {
        return mergeModels(false);
    }

    public List<ApiModelSummary> publicModels() {
        return mergeModels(true);
    }

    public void setModelEnabled(String modelKey, boolean enabled) {
        repository.setModelEnabled(modelKey, enabled);
    }

    public String baseUrl() {
        return publicBaseUrl.replaceAll("/+$", "") + "/api/v1";
    }

    private List<ApiModelSummary> mergeModels(boolean onlyApiEnabled) {
        Set<String> enabled = new HashSet<>(repository.enabledModelKeys());
        Set<String> seen = new HashSet<>();
        List<ApiModelSummary> result = new ArrayList<>();
        try {
            JsonNode models = objectMapper.readTree(aiServiceClient.listAllModels());
            for (JsonNode model : models) {
                String key = model.path("key").asText();
                seen.add(key);
                boolean apiEnabled = enabled.contains(key);
                if (onlyApiEnabled && !apiEnabled) {
                    continue;
                }
                List<String> capabilities = new ArrayList<>();
                model.path("capabilities").forEach(item -> capabilities.add(item.asText()));
                Integer contextWindow = model.path("context_window").isNumber()
                        ? model.path("context_window").asInt()
                        : null;
                result.add(new ApiModelSummary(
                        key,
                        model.path("display_name").asText(key),
                        model.path("category").asText("language"),
                        capabilities,
                        contextWindow,
                        apiEnabled,
                        true
                ));
            }
            if (!onlyApiEnabled) {
                enabled.stream()
                        .filter(key -> !seen.contains(key))
                        .forEach(key -> result.add(new ApiModelSummary(
                                key,
                                key,
                                "unknown",
                                List.of(),
                                null,
                                true,
                                false
                        )));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("AI service returned invalid model registry", exception);
        }
        return result;
    }
}
