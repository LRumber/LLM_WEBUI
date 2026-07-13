package com.lmplatform.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.chat.config.AiServiceProperties;
import com.lmplatform.chat.model.TitleGenerationResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;

/** HTTP boundary between the Java business service and the Python AI gateway. */
@Component
public class AiServiceClient {

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public AiServiceClient(AiServiceProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(properties.timeoutSeconds() * 1_000);
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /** Fetches the AI gateway's model registry as an unchanged JSON document. */
    public String listModels() {
        return restClient.get()
                .uri("/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
    }

    /** Fetches enabled and disabled registrations for administration. */
    public String listAllModels() {
        return restClient.get()
                .uri("/internal/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
    }

    /** Discovers model ids from an administrator-supplied OpenAI-compatible endpoint. */
    public String discoverModels(JsonNode connection) {
        return restClient.post()
                .uri("/internal/models/discover")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(connection)
                .retrieve()
                .body(String.class);
    }

    /** Creates or replaces a dynamic model registration in the AI gateway. */
    public String upsertModel(String key, JsonNode model) {
        return restClient.put()
                .uri("/internal/models/{key}", key)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(model)
                .retrieve()
                .body(String.class);
    }

    /** Removes a gateway registration without deleting model weights. */
    public void deleteModel(String key) {
        restClient.delete().uri("/internal/models/{key}", key).retrieve().toBodilessEntity();
    }

    /** Probes one enabled inference endpoint and returns its normalized health result. */
    public String checkModelHealth(String key) {
        return restClient.post()
                .uri("/internal/models/{key}/health", key)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
    }

    /** Forwards a JSON request to a normalized AI-service endpoint and returns the raw JSON response. */
    public String postJson(String uri, JsonNode body) {
        return restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /** Returns one registered model so proxy endpoints can expose OpenAI-compatible metadata. */
    public JsonNode getModel(String key) {
        String response = restClient.get()
                .uri("/v1/models/{key}", key)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        try {
            return objectMapper.readTree(response);
        } catch (IOException exception) {
            throw new IllegalStateException("AI service returned invalid model JSON", exception);
        }
    }

    /** Forwards audio transcription uploads to the AI service while preserving the selected model key. */
    public String transcribeAudioRaw(byte[] content, String filename, String mediaType, String model) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.parseMediaType(mediaType));
        return restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/v1/audio/transcriptions")
                        .queryParam("model", model)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(body.build())
                .retrieve()
                .body(String.class);
    }

    /** Sends one in-memory object to the isolated parser and returns normalized extraction JSON. */
    public JsonNode parseDocument(byte[] content, String filename, String mediaType) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.parseMediaType(mediaType));
        String response = restClient.post()
                .uri("/v1/documents/parse")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(body.build())
                .retrieve()
                .body(String.class);
        try {
            return objectMapper.readTree(response);
        } catch (IOException exception) {
            throw new IllegalStateException("Document parser returned invalid JSON", exception);
        }
    }

    /** Generates a batch of vectors through a registered OpenAI-compatible embedding model. */
    public List<float[]> embed(String model, List<String> inputs) {
        String response = restClient.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "input", inputs))
                .retrieve()
                .body(String.class);
        try {
            JsonNode data = objectMapper.readTree(response).path("data");
            List<float[]> vectors = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embedding = item.path("embedding");
                float[] vector = new float[embedding.size()];
                for (int index = 0; index < embedding.size(); index++) {
                    vector[index] = (float) embedding.get(index).asDouble();
                }
                vectors.add(vector);
            }
            if (vectors.size() != inputs.size()) throw new IllegalStateException("Incomplete embedding batch");
            return vectors;
        } catch (IOException exception) {
            throw new IllegalStateException("Embedding service returned invalid JSON", exception);
        }
    }

    /** Transcribes a browser recording through the AI gateway's local speech model boundary. */
    public String transcribeAudio(byte[] content, String filename, String mediaType, String model) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.parseMediaType(mediaType));
        String response = restClient.post()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/v1/audio/transcriptions");
                    if (model != null && !model.isBlank()) {
                        builder.queryParam("model", model);
                    }
                    return builder.build();
                })
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(body.build())
                .retrieve()
                .body(String.class);
        try {
            return objectMapper.readTree(response).path("text").asText();
        } catch (IOException exception) {
            throw new IllegalStateException("Speech service returned invalid JSON", exception);
        }
    }

    /** Scores candidate snippets through a registered rerank-capable model. */
    public List<RerankScore> rerank(String model, String query, List<String> documents) {
        String response = restClient.post()
                .uri("/v1/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "query", query, "documents", documents))
                .retrieve()
                .body(String.class);
        try {
            List<RerankScore> scores = new ArrayList<>();
            for (JsonNode item : objectMapper.readTree(response).path("results")) {
                scores.add(new RerankScore(
                        item.path("index").asInt(),
                        item.path("relevance_score").asDouble()
                ));
            }
            return scores;
        } catch (IOException exception) {
            throw new IllegalStateException("Rerank service returned invalid JSON", exception);
        }
    }

    /**
     * Sends an OpenAI-compatible conversation and forwards each normalized SSE event as it arrives.
     * The response is consumed inside the exchange callback so the underlying connection stays open.
     */
    public void streamChat(
            String model,
            List<? extends Map<String, ?>> messages,
            boolean thinkingEnabled,
            EventConsumer consumer
    )
            throws IOException {
        Map<String, Object> payload = Map.of(
                "model", model, "messages", messages, "thinking_enabled", thinkingEnabled
        );
        restClient.post()
                .uri("/v1/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(payload)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        String detail = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw new IOException(
                                "AI service returned " + response.getStatusCode().value() + ": " + detail
                        );
                    }
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            String data = line.substring(5).strip();
                            if (!data.isEmpty()) {
                                consumer.accept(objectMapper.readTree(data));
                            }
                        }
                    }
                    return null;
                });
    }

    /** Reuses the streaming protocol for title generation and collects text plus token usage. */
    public TitleGenerationResult generateTitle(String model, List<Map<String, String>> messages)
            throws IOException {
        StringBuilder title = new StringBuilder();
        AtomicInteger inputTokens = new AtomicInteger();
        AtomicInteger outputTokens = new AtomicInteger();
        streamChat(model, messages, false, event -> {
            switch (event.path("type").asText()) {
                case "delta" -> title.append(event.path("content").asText());
                case "usage" -> {
                    inputTokens.set(event.path("prompt_tokens").asInt());
                    outputTokens.set(event.path("completion_tokens").asInt());
                }
                case "error" -> throw new IOException(event.path("message").asText("Title generation failed"));
                default -> {
                    // Metadata and completion markers do not carry title content.
                }
            }
        });
        return new TitleGenerationResult(title.toString(), inputTokens.get(), outputTokens.get());
    }

    /** Callback that may stop the upstream stream when the downstream client disconnects. */
    @FunctionalInterface
    public interface EventConsumer {
        void accept(JsonNode event) throws IOException;
    }

    /** One rerank score referencing the original candidate list index. */
    public record RerankScore(int index, double relevanceScore) {
    }
}
