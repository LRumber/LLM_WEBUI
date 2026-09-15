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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.http.HttpServletResponse;
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
            @RequestBody JsonNode request,
            HttpServletResponse response
    ) throws IOException {
        ApiPrincipal principal = service.authenticate(authorization);
        service.ensureQuota(principal);
        String model = request.path("model").asText();
        service.ensureModelEnabled(model);
        if (request.path("stream").asBoolean(false)) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            streamChat(principal, model, request, response.getOutputStream());
            return null;
        }
        return ResponseEntity.ok(rawChat(principal, model, request));
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

    private String rawChat(ApiPrincipal principal, String model, JsonNode request) {
        long started = System.nanoTime();
        try {
            String response = aiServiceClient.postJson("/v1/chat/completions", request);
            JsonNode usage = objectMapper.readTree(response).path("usage");
            repository.recordUsage(
                    UUID.randomUUID(), principal, model, "chat/completions",
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    "completed", null, elapsedMs(started)
            );
            return response;
        } catch (IOException exception) {
            repository.recordUsage(
                    UUID.randomUUID(), principal, model, "chat/completions",
                    0, 0, "failed", "upstream_error", elapsedMs(started)
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
        try {
            aiServiceClient.streamJson("/v1/chat/completions", request, line -> {
                try {
                    captureUsage(line, promptTokens, completionTokens);
                    output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } catch (IOException exception) {
                    throw new StreamRelayException(exception);
                }
            });
            repository.recordUsage(
                    UUID.randomUUID(), principal, model, "chat/completions",
                    promptTokens.get(), completionTokens.get(), "completed", null, elapsedMs(started)
            );
        } catch (StreamRelayException exception) {
            repository.recordUsage(
                    UUID.randomUUID(), principal, model, "chat/completions",
                    promptTokens.get(), completionTokens.get(), "failed", "client_disconnected", elapsedMs(started)
            );
            throw exception.ioException();
        } catch (IOException exception) {
            repository.recordUsage(
                    UUID.randomUUID(), principal, model, "chat/completions",
                    promptTokens.get(), completionTokens.get(), "failed", "upstream_error", elapsedMs(started)
            );
            throw exception;
        }
    }

    private void captureUsage(String line, AtomicInteger promptTokens, AtomicInteger completionTokens) {
        if (!line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).strip();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return;
        }
        try {
            JsonNode usage = objectMapper.readTree(data).path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                promptTokens.set(usage.path("prompt_tokens").asInt(0));
                completionTokens.set(usage.path("completion_tokens").asInt(0));
            }
        } catch (IOException ignored) {
            // Some providers send comments or non-JSON keepalive lines.
        }
    }

    private int elapsedMs(long started) {
        return (int) ((System.nanoTime() - started) / 1_000_000);
    }

    private static class StreamRelayException extends RuntimeException {
        private final IOException ioException;

        StreamRelayException(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }

        IOException ioException() {
            return ioException;
        }
    }
}
