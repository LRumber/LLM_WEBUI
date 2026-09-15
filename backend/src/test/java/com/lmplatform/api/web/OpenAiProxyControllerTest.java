package com.lmplatform.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.api.model.ApiPrincipal;
import com.lmplatform.api.repository.ApiGatewayRepository;
import com.lmplatform.api.service.ApiGatewayService;
import com.lmplatform.chat.client.AiServiceClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for the OpenAI-compatible API proxy surface. */
class OpenAiProxyControllerTest {

    @Test
    void writesStreamingChatCompletionDirectlyToServletResponse() throws Exception {
        ApiGatewayService service = mock(ApiGatewayService.class);
        ApiGatewayRepository repository = mock(ApiGatewayRepository.class);
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiProxyController controller = new OpenAiProxyController(
                service, repository, aiServiceClient, objectMapper
        );
        ApiPrincipal principal = new ApiPrincipal(UUID.randomUUID(), 7L, "sk-sitp", 10_000L, 0L);
        when(service.authenticate("Bearer sk-sitp-test")).thenReturn(principal);
        doAnswer(invocation -> {
            java.util.function.Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}");
            consumer.accept("");
            consumer.accept("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}");
            consumer.accept("");
            consumer.accept("data: [DONE]");
            consumer.accept("");
            return null;
        }).when(aiServiceClient).streamJson(eq("/v1/chat/completions"), any(), any());

        MockHttpServletResponse response = new MockHttpServletResponse();
        var request = objectMapper.readTree("""
                {
                  "model": "qwen3-coder",
                  "messages": [{"role": "user", "content": "hello"}],
                  "stream": true
                }
                """);

        assertThat(controller.chat("Bearer sk-sitp-test", request, response)).isNull();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(response.getContentAsString()).contains(
                "data:", "\"content\":\"ok\"", "\"prompt_tokens\":1", "[DONE]"
        );
        verify(service).ensureModelEnabled("qwen3-coder");
        verify(aiServiceClient).streamJson(eq("/v1/chat/completions"), any(), any());
    }
}
