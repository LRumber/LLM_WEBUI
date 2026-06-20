package com.lmplatform.admin.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.lmplatform.chat.client.AiServiceClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    public String discover(@RequestBody JsonNode connection) {
        return aiServiceClient.discoverModels(connection);
    }

    /** Validates and persists one model through the provider-neutral AI gateway. */
    @PutMapping(value = "/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String upsert(@PathVariable String key, @RequestBody JsonNode model) {
        return aiServiceClient.upsertModel(key, model);
    }

    /** Deletes only registration metadata, never local weights or engine containers. */
    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        aiServiceClient.deleteModel(key);
        return ResponseEntity.noContent().build();
    }

    /** Runs a bounded health probe against the model's OpenAI-compatible endpoint. */
    @PostMapping(value = "/{key}/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public String health(@PathVariable String key) {
        return aiServiceClient.checkModelHealth(key);
    }
}
