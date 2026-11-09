package com.lmplatform.api.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.api.model.ApiPrincipal;
import com.lmplatform.api.repository.ApiGatewayRepository;
import com.lmplatform.chat.client.AiServiceClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiGatewayServiceTest {

    private final ApiGatewayService service = new ApiGatewayService(
            mock(ApiGatewayRepository.class),
            mock(AiServiceClient.class),
            new ObjectMapper(),
            "http://localhost:8505"
    );

    @Test
    void unlimitedQuotaDoesNotRejectUsageAboveStoredAmount() {
        var principal = new ApiPrincipal(UUID.randomUUID(), 7L, "sk-sitp", 10L, 20L, true);

        assertThatCode(() -> service.ensureQuota(principal)).doesNotThrowAnyException();
    }

    @Test
    void fixedQuotaStillRejectsExhaustedUsage() {
        var principal = new ApiPrincipal(UUID.randomUUID(), 7L, "sk-sitp", 10L, 10L, false);

        assertThatThrownBy(() -> service.ensureQuota(principal))
                .isInstanceOf(ApiGatewayException.class)
                .hasMessageContaining("quota exceeded");
    }
}
