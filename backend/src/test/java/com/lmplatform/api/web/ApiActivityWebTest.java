package com.lmplatform.api.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lmplatform.api.model.ApiPage;
import com.lmplatform.api.repository.ApiCallLogRepository;
import com.lmplatform.api.repository.ApiCreditRequestRepository;
import com.lmplatform.api.service.ApiCallTrace;
import com.lmplatform.api.service.ApiGatewayException;
import com.lmplatform.admin.config.AdminAuthenticationInterceptor;
import com.lmplatform.auth.web.UserSession;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiActivityWebTest {
    @Test
    void userCannotOverrideOwnershipAndMissingSessionIsDenied() throws Exception {
        var requests = mock(ApiCreditRequestRepository.class);
        var logs = mock(ApiCallLogRepository.class);
        when(logs.list(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new ApiPage<>(List.of(), 0, 0, 20, 0));
        var mvc = MockMvcBuilders.standaloneSetup(new DeveloperActivityController(requests, logs))
                .setControllerAdvice(new ApiGatewayExceptionHandler()).build();
        mvc.perform(get("/api/developer/call-logs").param("userId", "2").sessionAttr(UserSession.USER_ID_KEY, 1L))
                .andExpect(status().isOk());
        verify(logs).list(1L, "all", "", "", "", "", 0, 20);
        mvc.perform(get("/api/developer/call-logs")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/developer/credit-requests").sessionAttr(UserSession.USER_ID_KEY, 1L)
                .contentType("application/json").content("{\"type\":\"tokens\",\"amount\":10,\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(requests);
    }

    @Test
    void adminRoutesRejectOrdinaryUserSessions() throws Exception {
        var requests = mock(ApiCreditRequestRepository.class);
        var logs = mock(ApiCallLogRepository.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AdminApiActivityController(requests, logs))
                .addInterceptors(new AdminAuthenticationInterceptor()).build();
        mvc.perform(get("/api/admin/api/call-logs").sessionAttr(UserSession.USER_ID_KEY, 1L))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/api/credit-requests/" + UUID.randomUUID() + "/review")
                .sessionAttr(UserSession.USER_ID_KEY, 1L).contentType("application/json").content("{\"approved\":true,\"approvedTokens\":100}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(requests, logs);
    }

    @Test
    void filterCapturesEarlyFailureAndAlwaysClearsRequestState() throws Exception {
        var logs = mock(ApiCallLogRepository.class);
        var filter = new ApiCallLogFilter(logs);
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/models"), response, (req, res) -> {
            ApiCallTrace.failed(new ApiGatewayException("quota_exceeded", "not enough", 429));
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(429);
        });
        assertThat(response.getHeader("X-Request-ID")).isNotBlank();
        var trace = org.mockito.ArgumentCaptor.forClass(ApiCallTrace.class);
        verify(logs).record(trace.capture(), eq("/api/v1/models"), eq(429));
        assertThat(trace.getValue().failure.code()).isEqualTo("quota_exceeded");
        assertThat(ApiCallTrace.current()).isNull();
    }

    @Test
    void diagnosticFailureCannotInterruptOrRebillActualRequest() throws Exception {
        var logs = mock(ApiCallLogRepository.class);
        doThrow(new IllegalStateException("database down")).when(logs).record(any(), anyString(), anyInt());
        var response = new MockHttpServletResponse();
        new ApiCallLogFilter(logs).doFilter(new MockHttpServletRequest("GET", "/api/v1/models"), response,
                (req, res) -> res.getWriter().write("original response"));
        assertThat(response.getContentAsString()).isEqualTo("original response");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(ApiCallTrace.current()).isNull();
    }
}
