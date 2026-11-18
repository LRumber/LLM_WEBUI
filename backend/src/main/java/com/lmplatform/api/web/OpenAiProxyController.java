package com.lmplatform.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lmplatform.api.model.ApiPrincipal;
import com.lmplatform.api.repository.ApiGatewayRepository;
import com.lmplatform.api.repository.ApiCreditRepository;
import com.lmplatform.api.service.ApiGatewayException;
import com.lmplatform.api.service.InsufficientApiCreditException;
import com.lmplatform.api.service.ApiGatewayService;
import com.lmplatform.api.service.ApiCallTrace;
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
    private final ApiCreditRepository credits;

    public OpenAiProxyController(
            ApiGatewayService service,
            ApiGatewayRepository repository,
            AiServiceClient aiServiceClient,
            ObjectMapper objectMapper,
            ApiCreditRepository credits
    ) {
        this.service = service;
        this.repository = repository;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
        this.credits = credits;
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
        ApiCallTrace.request(request.path("model").asText(""), request.path("stream").asBoolean(false));
        ApiPrincipal principal = service.authenticate(authorization);
        service.ensureQuota(principal);
        String model = request.path("model").asText();
        service.ensureModelEnabled(model);
        if (!request.isObject()) throw new ApiGatewayException("invalid_request", "Request must be an object", 400);
        ObjectNode prepared = request.deepCopy();
        // This fallback is only a credit reservation estimate; upstream controls omitted output limits.
        long output = request.path("max_completion_tokens").asLong(request.path("max_tokens").asLong(2048));
        long copies = request.path("n").asLong(1);
        if (output < 1 || output > Integer.MAX_VALUE || copies < 1 || copies > 128) {
            throw new ApiGatewayException("invalid_request", "Invalid output token limit or n", 400);
        }
        if (prepared.path("stream").asBoolean(false)) {
            ObjectNode options = prepared.withObject("/stream_options");
            options.put("include_usage", true);
        }
        UUID reservation = reserveChat(principal.userId(), request, output * copies);
        try {
        if (request.path("stream").asBoolean(false)) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            streamChat(principal, model, prepared, response.getOutputStream(), reservation);
            return null;
        }
        return ResponseEntity.ok(rawChat(principal, model, prepared, reservation));
        } finally {
            credits.release(reservation);
        }
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
        ApiCallTrace.request(model, false);
        ApiPrincipal principal = service.authenticate(authorization);
        service.ensureQuota(principal);
        service.ensureModelEnabled(model);
        long started = System.nanoTime();
        long speechBudget = service.publicModels().stream().filter(item -> item.key().equals(model))
                .mapToLong(item -> item.contextWindow() == null ? 8192L : Math.max(8192L, item.contextWindow()))
                .findFirst().orElse(8192L);
        UUID reservation = credits.reserve(principal.userId(), speechBudget);
        try {
        String response = aiServiceClient.transcribeAudioRaw(
                file.getBytes(),
                file.getOriginalFilename() == null ? "audio.webm" : file.getOriginalFilename(),
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                model
        );
        JsonNode payload = objectMapper.readTree(response);
        boolean failed = capturePayloadError(payload);
        JsonNode audioUsage = payload.path("usage");
        repository.recordUsage(
                reservation, principal, model, "audio/transcriptions",
                audioUsage.path("input_tokens").asInt(audioUsage.path("prompt_tokens").asInt(0)),
                audioUsage.path("output_tokens").asInt(audioUsage.path("completion_tokens").asInt(textTokens(payload.path("text").asText("")))),
                failed ? "failed" : "completed", failed ? "upstream_error" : null, elapsedMs(started), !audioUsage.isObject()
        );
        return ResponseEntity.ok(response);
        } catch (IOException | RuntimeException exception) {
            ApiCallTrace.failed(exception);
            repository.recordUsage(reservation, principal, model, "audio/transcriptions",
                    0, 0, "failed", "upstream_error", elapsedMs(started), true);
            throw exception;
        } finally {
            credits.release(reservation);
        }
    }

    private ResponseEntity<String> proxyJson(
            String authorization, JsonNode request, String uri, String endpoint
    ) {
        ApiCallTrace.request(request.path("model").asText(""), false);
        ApiPrincipal principal = service.authenticate(authorization);
        service.ensureQuota(principal);
        String model = request.path("model").asText();
        service.ensureModelEnabled(model);
        long started = System.nanoTime();
        UUID reservation = credits.reserve(principal.userId(), estimate(request));
        try {
        String response = aiServiceClient.postJson(uri, request);
        int inputTokens = textTokens(request.toString());
        int outputTokens = 0;
        boolean estimated = true;
        boolean failed = false;
        try {
            JsonNode payload = objectMapper.readTree(response);
            failed = capturePayloadError(payload);
            JsonNode usage = payload.path("usage");
            inputTokens = usage.path("prompt_tokens").asInt(usage.path("total_tokens").asInt(inputTokens));
            outputTokens = usage.path("completion_tokens").asInt(0);
            estimated = !usage.isObject() || (!usage.has("prompt_tokens") && !usage.has("total_tokens"));
        } catch (IOException ignored) {
            // Some providers omit usage for non-chat endpoints.
        }
        repository.recordUsage(
                reservation, principal, model, endpoint,
                inputTokens, outputTokens, failed ? "failed" : "completed", failed ? "upstream_error" : null, elapsedMs(started), estimated
        );
        return ResponseEntity.ok(response);
        } catch (RuntimeException exception) {
            ApiCallTrace.failed(exception);
            repository.recordUsage(reservation, principal, model, endpoint,
                    0, 0, "failed", "upstream_error", elapsedMs(started), true);
            throw exception;
        } finally {
            credits.release(reservation);
        }
    }

    private String rawChat(ApiPrincipal principal, String model, JsonNode request, UUID reservation) {
        long started = System.nanoTime();
        try {
            String response = aiServiceClient.postJson("/v1/chat/completions", request);
            JsonNode payload = objectMapper.readTree(response);
            boolean failed = capturePayloadError(payload);
            JsonNode usage = payload.path("usage");
            repository.recordUsage(
                    reservation, principal, model, "chat/completions",
                    usage.path("prompt_tokens").asInt(textTokens(request.toString())),
                    usage.path("completion_tokens").asInt(textTokens(payload.path("choices").toString())),
                    failed ? "failed" : "completed", failed ? "upstream_error" : null, elapsedMs(started), !usage.has("prompt_tokens") || !usage.has("completion_tokens")
            );
            return response;
        } catch (IOException exception) {
            ApiCallTrace.failed(exception);
            repository.recordUsage(
                    reservation, principal, model, "chat/completions",
                    0, 0, "failed", "upstream_error", elapsedMs(started), true
            );
            throw new ApiGatewayException("upstream_error", exception.getMessage(), 502);
        } catch (RuntimeException exception) {
            ApiCallTrace.failed(exception);
            repository.recordUsage(reservation, principal, model, "chat/completions",
                    0, 0, "failed", "upstream_error", elapsedMs(started), true);
            throw exception;
        }
    }

    private void streamChat(
            ApiPrincipal principal, String model, JsonNode request, OutputStream output, UUID reservation
    ) throws IOException {
        AtomicInteger promptTokens = new AtomicInteger();
        AtomicInteger completionTokens = new AtomicInteger();
        StringBuilder emittedText = new StringBuilder();
        java.util.concurrent.atomic.AtomicLong renewedAt = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        java.util.concurrent.atomic.AtomicBoolean hasUsage = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean upstreamFailed = new java.util.concurrent.atomic.AtomicBoolean();
        long started = System.nanoTime();
        try {
            aiServiceClient.streamJson("/v1/chat/completions", request, line -> {
                try {
                    if (line.startsWith("data:")) {
                        try {
                            JsonNode chunk = objectMapper.readTree(line.substring(5).strip());
                            if (chunk != null && chunk.hasNonNull("error")) {
                                upstreamFailed.set(true);
                                ApiCallTrace.failed(new IOException("Upstream error: " + chunk.path("error").toString()));
                            }
                        } catch (IOException ignored) { }
                    }
                    if (System.nanoTime() - renewedAt.get() > java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) {
                        credits.renew(reservation);
                        renewedAt.set(System.nanoTime());
                    }
                    if (captureUsage(line, promptTokens, completionTokens)) hasUsage.set(true);
                    if (!hasUsage.get() && line.startsWith("data:")) {
                        try {
                            JsonNode chunk = objectMapper.readTree(line.substring(5).strip());
                            for (JsonNode choice : chunk.path("choices")) {
                                JsonNode delta = choice.path("delta");
                                emittedText.append(delta.path("content").asText(""));
                                emittedText.append(delta.path("reasoning_content").asText(""));
                                if (delta.has("tool_calls")) emittedText.append(delta.path("tool_calls"));
                            }
                            promptTokens.set(textTokens(request.toString()));
                            completionTokens.set(textTokens(emittedText.toString()));
                        } catch (IOException ignored) { }
                    }
                    output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } catch (IOException exception) {
                    throw new StreamRelayException(exception);
                }
            });
            repository.recordUsage(
                    reservation, principal, model, "chat/completions",
                    promptTokens.get(), completionTokens.get(), upstreamFailed.get() ? "failed" : "completed",
                    upstreamFailed.get() ? "upstream_error" : null, elapsedMs(started), !hasUsage.get()
            );
        } catch (StreamRelayException exception) {
            ApiCallTrace.failed(new IOException("client_disconnected"));
            repository.recordUsage(
                    reservation, principal, model, "chat/completions",
                    promptTokens.get(), completionTokens.get(), "failed", "client_disconnected", elapsedMs(started), !hasUsage.get()
            );
            throw exception.ioException();
        } catch (IOException | RuntimeException exception) {
            ApiCallTrace.failed(exception);
            repository.recordUsage(
                    reservation, principal, model, "chat/completions",
                    promptTokens.get(), completionTokens.get(), "failed", "upstream_error", elapsedMs(started), !hasUsage.get()
            );
            throw exception;
        }
    }

    private boolean captureUsage(String line, AtomicInteger promptTokens, AtomicInteger completionTokens) {
        if (!line.startsWith("data:")) {
            return false;
        }
        String data = line.substring(5).strip();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return false;
        }
        try {
            JsonNode usage = objectMapper.readTree(data).path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                promptTokens.set(usage.path("prompt_tokens").asInt(0));
                completionTokens.set(usage.path("completion_tokens").asInt(0));
                return true;
            }
        } catch (IOException ignored) {
            // Some providers send comments or non-JSON keepalive lines.
        }
        return false;
    }

    private boolean capturePayloadError(JsonNode payload) {
        if (payload != null && payload.hasNonNull("error")) {
            ApiCallTrace.failed(new IOException("Upstream error: " + payload.path("error")));
            return true;
        }
        return false;
    }

    private UUID reserveChat(long userId, JsonNode request, long outputBudget) {
        int coarseInput = estimate(request);
        try {
            return credits.reserve(userId, coarseInput + outputBudget);
        } catch (InsufficientApiCreditException coarse) {
            if (coarse.available() <= outputBudget) throw coarse;
            JsonNode result = null;
            try {
                String body = aiServiceClient.postJson("/internal/chat/token-count", request);
                if (body != null) result = objectMapper.readTree(body);
            } catch (org.springframework.web.client.RestClientException | IOException ignored) {
                // An unsupported or unavailable tokenizer cannot relax the reservation.
            }
            JsonNode count = result == null ? null : result.get("count");
            if (count == null || !count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() <= 0) {
                throw new InsufficientApiCreditException(coarse.required(), coarse.available(), "上游分词不可用，按请求字节粗估");
            }
            try {
                return credits.reserve(userId, count.intValue() + outputBudget);
            } catch (InsufficientApiCreditException exact) {
                throw new InsufficientApiCreditException(exact.required(), exact.available(), "上游分词加输出预留");
            }
        }
    }

    private int estimate(JsonNode request) {
        return Math.max(1, request.toString().getBytes(StandardCharsets.UTF_8).length);
    }

    private int textTokens(String text) {
        if (text.isEmpty()) return 0;
        long ascii = text.codePoints().filter(c -> c < 128).count();
        return (int) Math.min(Integer.MAX_VALUE, (ascii + 3) / 4 + text.codePointCount(0, text.length()) - ascii);
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
