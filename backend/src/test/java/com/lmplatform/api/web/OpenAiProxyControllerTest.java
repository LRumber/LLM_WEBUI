package com.lmplatform.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.api.model.ApiPrincipal;
import com.lmplatform.api.repository.ApiGatewayRepository;
import com.lmplatform.api.repository.ApiCreditRepository;
import com.lmplatform.api.service.ApiGatewayService;
import com.lmplatform.api.service.ApiGatewayException;
import com.lmplatform.chat.client.AiServiceClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for the OpenAI-compatible API proxy surface. */
class OpenAiProxyControllerTest {

    @Test
    void forwardsOmittedAndExplicitOutputLimitsWithoutAddingOrChangingThem() throws Exception {
        var service = mock(ApiGatewayService.class);
        var repository = mock(ApiGatewayRepository.class);
        var client = mock(AiServiceClient.class);
        var credits = mock(ApiCreditRepository.class);
        var mapper = new ObjectMapper();
        var controller = new OpenAiProxyController(service, repository, client, mapper, credits);
        when(service.authenticate("Bearer test")).thenReturn(new ApiPrincipal(UUID.randomUUID(), 7L, "test", 10000, 0, false));
        when(credits.reserve(eq(7L), org.mockito.ArgumentMatchers.anyLong())).thenReturn(UUID.randomUUID());
        when(client.postJson(eq("/v1/chat/completions"), any())).thenAnswer(invocation -> {
            com.fasterxml.jackson.databind.JsonNode forwarded = invocation.getArgument(1);
            // Only caller-supplied limits should reach the model service.
            var expected = mapper.readTree(forwarded.path("messages").get(0).path("content").asText());
            assertThat(forwarded.get("max_tokens")).isEqualTo(expected.get("max_tokens"));
            assertThat(forwarded.get("max_completion_tokens")).isEqualTo(expected.get("max_completion_tokens"));
            return "{\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":3000}}";
        });
        doAnswer(invocation -> {
            com.fasterxml.jackson.databind.JsonNode forwarded = invocation.getArgument(1);
            assertThat(forwarded.has("max_tokens")).isFalse();
            assertThat(forwarded.has("max_completion_tokens")).isFalse();
            java.util.function.Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":3000}}");
            consumer.accept("data: [DONE]");
            return null;
        }).when(client).streamJson(eq("/v1/chat/completions"), any(), any());
        for (String limits : java.util.List.of("{}", "{\"max_tokens\":4096}", "{\"max_completion_tokens\":8192}")) {
            var request = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(limits);
            request.put("model", "test");
            request.putArray("messages").addObject().put("role", "user").put("content", limits);
            var result = controller.chat("Bearer test", request, new MockHttpServletResponse());
            assertThat(result.getBody()).asString().contains("3000");
        }
        var streamRequest = mapper.readTree("{\"model\":\"test\",\"stream\":true}");
        var response = new MockHttpServletResponse();
        controller.chat("Bearer test", streamRequest, response);
        assertThat(response.getContentAsString()).contains("3000", "[DONE]");
        assertThat(streamRequest.has("max_tokens")).isFalse();
    }

    @Test
    void insufficientReservationNeverCallsUpstreamOrCommitsStream() throws Exception {
        var service = mock(ApiGatewayService.class);
        var repository = mock(ApiGatewayRepository.class);
        var client = mock(AiServiceClient.class);
        var credits = mock(ApiCreditRepository.class);
        var mapper = new ObjectMapper();
        var controller = new OpenAiProxyController(service, repository, client, mapper, credits);
        when(service.authenticate("Bearer test")).thenReturn(new ApiPrincipal(UUID.randomUUID(), 7L, "test", 100, 0, false));
        when(credits.reserve(eq(7L), org.mockito.ArgumentMatchers.anyLong()))
                .thenThrow(new ApiGatewayException("quota_exceeded", "Insufficient balance", 429));
        var response = new MockHttpServletResponse();
        var request = mapper.readTree("{\"model\":\"test\",\"stream\":true}");
        assertThatThrownBy(() -> controller.chat("Bearer test", request, response)).isInstanceOf(ApiGatewayException.class);
        assertThat(response.isCommitted()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void upstreamFailureReleasesReservation() throws Exception {
        var service = mock(ApiGatewayService.class);
        var repository = mock(ApiGatewayRepository.class);
        var client = mock(AiServiceClient.class);
        var credits = mock(ApiCreditRepository.class);
        var mapper = new ObjectMapper();
        var controller = new OpenAiProxyController(service, repository, client, mapper, credits);
        UUID reservation = UUID.randomUUID();
        when(service.authenticate("Bearer test")).thenReturn(new ApiPrincipal(UUID.randomUUID(), 7L, "test", 10000, 0, false));
        when(credits.reserve(eq(7L), org.mockito.ArgumentMatchers.anyLong())).thenReturn(reservation);
        when(client.postJson(eq("/v1/chat/completions"), any())).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> controller.chat("Bearer test", mapper.readTree("{\"model\":\"test\"}"), new MockHttpServletResponse()))
                .isInstanceOf(IllegalStateException.class);
        verify(credits).release(reservation);
    }

    @Test
    void writesStreamingChatCompletionDirectlyToServletResponse() throws Exception {
        ApiGatewayService service = mock(ApiGatewayService.class);
        ApiGatewayRepository repository = mock(ApiGatewayRepository.class);
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiProxyController controller = new OpenAiProxyController(
                service, repository, aiServiceClient, objectMapper, mock(ApiCreditRepository.class)
        );
        ApiPrincipal principal = new ApiPrincipal(UUID.randomUUID(), 7L, "sk-sitp", 10_000L, 0L, false);
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
