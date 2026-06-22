package com.lmplatform.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.chat.client.AiServiceClient;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.junit.jupiter.api.Test;

/** Unit tests for the authenticated model-management proxy contract. */
class AdminModelControllerTest {

    @Test
    void proxiesDynamicModelRegistration() throws Exception {
        AiServiceClient client = mock(AiServiceClient.class);
        var model = new ObjectMapper().readTree("""
                {"key":"local-chat","display_name":"Local Chat"}
                """);
        when(client.upsertModel("local-chat", model)).thenReturn(model.toString());

        assertThat(new AdminModelController(client).upsert("local-chat", model).getBody())
                .contains("local-chat");
        verify(client).upsertModel("local-chat", model);
    }

    /** Confirms discovery credentials are forwarded without being logged or persisted by Java. */
    @Test
    void proxiesOpenAiCompatibleModelDiscovery() throws Exception {
        AiServiceClient client = mock(AiServiceClient.class);
        var connection = new ObjectMapper().readTree("""
                {"base_url":"https://example.invalid/v1","api_key":"temporary-secret"}
                """);
        when(client.discoverModels(connection)).thenReturn("[\"chat-model\",\"vision-model\"]");

        assertThat(new AdminModelController(client).discover(connection).getBody())
                .contains("chat-model", "vision-model");
        verify(client).discoverModels(connection);
    }

    /** Keeps FastAPI/Pydantic validation details visible to the administrator form. */
    @Test
    void returnsGatewayValidationDetails() throws Exception {
        AiServiceClient client = mock(AiServiceClient.class);
        var model = new ObjectMapper().readTree("""
                {"key":"bad","base_url":"not-a-url"}
                """);
        when(client.upsertModel("bad", model)).thenThrow(HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Unprocessable Entity",
                HttpHeaders.EMPTY,
                "{\"detail\":\"base_url must be a valid URL\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        ));

        var response = new AdminModelController(client).upsert("bad", model);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).contains("base_url must be a valid URL");
    }

    @Test
    void deletesOnlyModelRegistration() {
        AiServiceClient client = mock(AiServiceClient.class);

        assertThat(new AdminModelController(client).delete("local-chat").getStatusCode().value())
                .isEqualTo(204);
        verify(client).deleteModel("local-chat");
    }
}
