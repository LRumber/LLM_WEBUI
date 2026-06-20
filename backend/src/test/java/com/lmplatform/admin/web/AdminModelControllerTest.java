package com.lmplatform.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.chat.client.AiServiceClient;
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

        assertThat(new AdminModelController(client).upsert("local-chat", model))
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

        assertThat(new AdminModelController(client).discover(connection))
                .contains("chat-model", "vision-model");
        verify(client).discoverModels(connection);
    }

    @Test
    void deletesOnlyModelRegistration() {
        AiServiceClient client = mock(AiServiceClient.class);

        assertThat(new AdminModelController(client).delete("local-chat").getStatusCode().value())
                .isEqualTo(204);
        verify(client).deleteModel("local-chat");
    }
}
