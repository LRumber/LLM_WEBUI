package com.lmplatform.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lmplatform.api.model.ApiPrincipal;
import com.lmplatform.api.repository.ApiGatewayRepository;
import com.lmplatform.api.service.ApiGatewayException;
import com.lmplatform.api.service.ApiGatewayService;
import com.lmplatform.chat.client.AiServiceClient;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1")
public class OpenAiProxyController {

    private final ApiGatewayService service;
    private final ApiGatewayRepository repository;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;

    public OpenAiProxyController(
            ApiGatewayService service,
            ApiGatewayRepository repository,
            AiServiceClient aiServiceClient,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.repository = repository;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode models(@RequestHeader(value = "Authorization", required = false) String authorization) {
        ApiPrincipal principal = service.authenticate(authorization);
        service.ensureQuota(principal);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("object", "list");
        ArrayNode data = response.putArray("data");
        service.publicModels().forEach(model -> {
            ObjectNode item = data.addObject();
            item.put("id", model.key());
            item.put("object", "model");
            item.put("created", 0);
            item.put("owned_by", "sitp-ai-platform");
            item.put("display_name", model.displayName());
            item.put("category", model.category());
        });
        return response;
    }

    @PostMapping(value = "/chat/completions", produces = {
            MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE
    })
    public ResponseEntity<?> chat(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody JsonNode request
    ) {
        ApiPrincipal principal = service.authenticate(authorization);
        service.ensureQuota(principal);
        String model = request.path("model").asText();
        service.ensureModelEnabled(model);
        if (request.path("stream").asBoolean(false)) {
            StreamingResponseBody body = output -> streamChat(principal, model, request, output);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("Cache-Control", "no-cache")
                    .header("X-Accel-Buffering", "no")
                    .body(body);
        }
        return ResponseEntity.ok(nonStreamingChat(principal, model, request));
    }

    @PostMapping(value = "/embeddings", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> embeddings(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody JsonNode request
    ) {
        return proxyJson(authorization, request, "/v1/embeddings", "embeddings");
    }

    @PostMapping(value = "/rerank", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> rerank(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody JsonNode request
    ) {
        return proxyJson(authorization, request, "/v1/rerank", "rerank");
    }

    @PostMapping(
            value = "/audio/transcriptions",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> transcribe(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile file,
            @RequestParam("model") String model
    ) throws IOException {
        ApiPrincipal principal = service.authenticate(authorization);
        service.ensureQuota(principal);
        service.ensureModelEnabled(model);
        long started = System.nanoTime();
        String response = aiServiceClient.transcribeAudioRaw(
                file.getBytes(),
                file.getOriginalFilename() == null ? "audio.webm" : file.getOriginalFilename(),
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                model
        );
        repository.recordUsage(
                UUID.randomUUID(), principal, model, "audio/transcriptions",
                0, 0, "completed", null, elapsedMs(started)
        );
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<String> proxyJson(
            String authorization, JsonNode request, String uri, String endpoint
    ) {
        ApiPrincipal principal = service.authenticate(authorization);
        service.ensureQuota(principal);
        String model = request.path("model").asText();
        service.ensureModelEnabled(model);
        long started = System.nanoTime();
        String response = aiServiceClient.postJson(uri, request);
        int inputTokens = 0;
        int outputTokens = 0;
        try {
            JsonNode usage = objectMapper.readTree(response).path("usage");
            inputTokens = usage.path("prompt_tokens").asInt(0);
            outputTokens = usage.path("completion_tokens").asInt(0);
        } catch (IOException ignored) {
            // Some providers omit usage for non-chat endpoints.
        }
        repository.recordUsage(
                UUID.randomUUID(), principal, model, endpoint,
                inputTokens, outputTokens, "completed", null, elapsedMs(started)
        );
        return ResponseEntity.ok(response);
    }

    private ObjectNode nonStreamingChat(ApiPrincipal principal, String model, JsonNode request) {
        StringBuilder content = new StringBuilder();
        AtomicInteger promptTokens = new AtomicInteger();
        AtomicInteger completionTokens = new AtomicInteger();
        long started = System.nanoTime();
        try {
            aiServiceClient.streamChat(model, messages(request), false, event -> {
                if ("delta".equals(event.path("type").asText())) {
                    content.append(event.path("content").asText());
                } else if ("usage".equals(event.path("type").asText())) {
                    promptTokens.set(event.path("prompt_tokens").asInt());
                    completionTokens.set(event.path("completion_tokens").asInt());
                } else if ("error".equals(event.path("type").asText())) {
                    throw new IOException(event.path("message").asText("Model call failed"));
                }
            });
            repository.recordUsage(
                    UUID.randomUUID(), principal, model, "chat/completions",
                    promptTokens.get(), completionTokens.get(), "completed", null, elapsedMs(started)
            );
            return chatCompletion(model, content.toString(), promptTokens.get(), completionTokens.get());
        } catch (IOException exception) {
            repository.recordUsage(
                    UUID.randomUUID(), principal, model, "chat/completions",
                    promptTokens.get(), completionTokens.get(), "failed", "upstream_error", elapsedMs(started)
            );
            throw new ApiGatewayException("upstream_error", exception.getMessage(), 502);
        }
    }

    private void streamChat(
            ApiPrincipal principal, String model, JsonNode request, OutputStream output
    ) throws IOException {
        AtomicInteger promptTokens = new AtomicInteger();
        AtomicInteger completionTokens = new AtomicInteger();
        long started = System.nanoTime();
        String id = "chatcmpl-" + UUID.randomUUID();
        try {
            aiServiceClient.streamChat(model, messages(request), false, event -> {
                String type = event.path("type").asText();
                if ("delta".equals(type)) {
                    writeSse(output, chatChunk(id, model, event.path("content").asText(), false));
                } else if ("usage".equals(type)) {
                    promptTokens.set(event.path("prompt_tokens").asInt());
                    completionTokens.set(event.path("completion_tokens").asInt());
                } else if ("error".equals(type)) {
                    throw new IOException(event.path("message").asText("Model call failed"));
                }
            });
            writeSse(output, chatChunk(id, model, "", true));
            output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            repository.recordUsage(
                    UUID.randomUUID(), principal, model, "chat/completions",
                    promptTokens.get(), completionTokens.get(), "completed", null, elapsedMs(started)
            );
        } catch (IOException exception) {
            repository.recordUsage(
                    UUID.randomUUID(), principal, model, "chat/completions",
                    promptTokens.get(), completionTokens.get(), "failed", "upstream_error", elapsedMs(started)
            );
            throw exception;
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.List<Map<String, ?>> messages(JsonNode request) {
        return objectMapper.convertValue(request.path("messages"), java.util.List.class);
    }

    private ObjectNode chatCompletion(String model, String content, int promptTokens, int completionTokens) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", "chatcmpl-" + UUID.randomUUID());
        response.put("object", "chat.completion");
        response.put("created", Instant.now().getEpochSecond());
        response.put("model", model);
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", "stop");
        response.putArray("choices").add(choice);
        response.set("usage", usage(promptTokens, completionTokens));
        return response;
    }

    private ObjectNode chatChunk(String id, String model, String content, boolean done) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", id);
        response.put("object", "chat.completion.chunk");
        response.put("created", Instant.now().getEpochSecond());
        response.put("model", model);
        ObjectNode delta = objectMapper.createObjectNode();
        if (!done) {
            delta.put("content", content);
        }
        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (done) {
            choice.put("finish_reason", "stop");
        } else {
            choice.putNull("finish_reason");
        }
        response.putArray("choices").add(choice);
        return response;
    }

    private ObjectNode usage(int promptTokens, int completionTokens) {
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);
        return usage;
    }

    private void writeSse(OutputStream output, ObjectNode node) throws IOException {
        output.write(("data: " + objectMapper.writeValueAsString(node) + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private int elapsedMs(long started) {
        return (int) ((System.nanoTime() - started) / 1_000_000);
    }
}
