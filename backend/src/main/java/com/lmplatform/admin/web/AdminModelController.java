package com.lmplatform.admin.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.lmplatform.chat.client.AiServiceClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated administration proxy for dynamic AI gateway model registrations. */
@RestController
@RequestMapping("/api/admin/models")
public class AdminModelController {

    private final AiServiceClient aiServiceClient;

    public AdminModelController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    /** Lists all registrations, including models currently disabled for users. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public String models() {
        return aiServiceClient.listAllModels();
    }

    /** Recognizes model ids without persisting the submitted credential or connection. */
    @PostMapping(value = "/discover", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> discover(@RequestBody JsonNode connection) {
        try {
            return ResponseEntity.ok(aiServiceClient.discoverModels(connection));
        } catch (RestClientResponseException exception) {
            return proxyError(exception);
        } catch (ResourceAccessException exception) {
            return gatewayUnavailable(exception);
        }
    }

    /** Validates and persists one model through the provider-neutral AI gateway. */
    @PutMapping(value = "/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> upsert(@PathVariable String key, @RequestBody JsonNode model) {
        try {
            return ResponseEntity.ok(aiServiceClient.upsertModel(key, model));
        } catch (RestClientResponseException exception) {
            return proxyError(exception);
        } catch (ResourceAccessException exception) {
            return gatewayUnavailable(exception);
        }
    }

    /** Deletes only registration metadata, never local weights or engine containers. */
    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        aiServiceClient.deleteModel(key);
        return ResponseEntity.noContent().build();
    }

    /** Runs a bounded health probe against the model's OpenAI-compatible endpoint. */
    @PostMapping(value = "/{key}/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> health(@PathVariable String key) {
        try {
            return ResponseEntity.ok(aiServiceClient.checkModelHealth(key));
        } catch (RestClientResponseException exception) {
            return proxyError(exception);
        } catch (ResourceAccessException exception) {
            return gatewayUnavailable(exception);
        }
    }

    /** Keeps AI-gateway validation details visible to the administrator UI. */
    private ResponseEntity<String> proxyError(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        return ResponseEntity.status(exception.getStatusCode())
                .contentType(MediaType.TEXT_PLAIN)
                .body(body.isBlank() ? exception.getMessage() : body);
    }

    /** Returns an actionable error when the Python AI gateway is not reachable. */
    private ResponseEntity<String> gatewayUnavailable(ResourceAccessException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.TEXT_PLAIN)
                .body("AI 服务不可用，请确认 AI 服务已启动并且 AI_SERVICE_BASE_URL 配置正确：" + exception.getMessage());
    }
}
