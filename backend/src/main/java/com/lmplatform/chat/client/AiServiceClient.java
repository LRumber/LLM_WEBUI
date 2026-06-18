package com.lmplatform.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.chat.config.AiServiceProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    public String listModels() {
        return restClient.get()
                .uri("/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
    }

    public void streamChat(String model, List<Map<String, String>> messages, EventConsumer consumer)
            throws IOException {
        Map<String, Object> payload = Map.of("model", model, "messages", messages);
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

    @FunctionalInterface
    public interface EventConsumer {
        void accept(JsonNode event) throws IOException;
    }
}
