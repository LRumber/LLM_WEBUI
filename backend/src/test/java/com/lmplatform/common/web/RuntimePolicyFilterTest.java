package com.lmplatform.common.web;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.common.model.RuntimePolicy;
import com.lmplatform.common.service.*;
import com.lmplatform.api.service.ApiGatewayService;
import com.lmplatform.auth.web.UserSession;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;

class RuntimePolicyFilterTest {
    final RuntimePolicyService policies=mock(RuntimePolicyService.class);
    final RuntimeAdmissionService admission=mock(RuntimeAdmissionService.class);
    MockHttpServletRequest request(String path) {var request=new MockHttpServletRequest("POST",path);request.getSession().setAttribute(UserSession.USER_ID_KEY,9L);return request;}
    @Test void maintenanceBlocksNewWorkButLeavesHistoryDownloadsAndAdminAvailable() throws Exception {
        when(policies.current()).thenReturn(new RuntimePolicy(0,0,0,true,"维护提示"));
        try(var filter=new RuntimePolicyFilter(policies,admission,mock(ApiGatewayService.class),new ObjectMapper())) {
            var response=new MockHttpServletResponse();filter.doFilter(request("/api/chat/stream"),response,(r,s)->{throw new AssertionError();});
            assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getContentAsString()).contains("维护提示");
            for(String path:new String[]{"/api/conversations","/api/files/abc/download","/api/admin/runtime-policy"}){
                var req=request(path);req.setMethod("GET");var res=new MockHttpServletResponse();filter.doFilter(req,res,(r,s)->s.getWriter().write("available"));assertThat(res.getContentAsString()).isEqualTo("available");
            }
        }
        verifyNoInteractions(admission);
    }
    @ParameterizedTest @ValueSource(strings={"/api/chat/stream","/api/conversations/c/messages/m/regenerate","/api/conversations/c/messages/m/edit-and-regenerate"})
    void retainsAsyncLeaseUntilCompletionAndCountsAllWebGenerationRoutes(String path) throws Exception {
        var policy=RuntimePolicy.defaults();when(policies.current()).thenReturn(policy);UUID id=UUID.randomUUID();when(admission.acquire(9,true,policy)).thenReturn(id);
        try(var filter=new RuntimePolicyFilter(policies,admission,mock(ApiGatewayService.class),new ObjectMapper())) {
            var req=request(path);req.setAsyncSupported(true);var res=new MockHttpServletResponse();
            filter.doFilter(req,res,(r,s)->r.startAsync());verify(admission,never()).release(any());
            req.getAsyncContext().complete();verify(admission).release(id);
        }
    }
    @Test void failuresReleaseSynchronousPermits() throws Exception {
        var policy=RuntimePolicy.defaults();when(policies.current()).thenReturn(policy);UUID id=UUID.randomUUID();when(admission.acquire(9,true,policy)).thenReturn(id);
        try(var filter=new RuntimePolicyFilter(policies,admission,mock(ApiGatewayService.class),new ObjectMapper())) {
            assertThatThrownBy(()->filter.doFilter(request("/api/chat/stream"),new MockHttpServletResponse(),(r,s)->{throw new java.io.IOException("disconnected");})).isInstanceOf(java.io.IOException.class);
            verify(admission).release(id);
        }
    }
}
