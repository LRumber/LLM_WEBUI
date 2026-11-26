package com.lmplatform.admin.web;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import com.lmplatform.admin.config.AdminAuthenticationInterceptor;
import com.lmplatform.admin.model.AdminPrincipal;
import com.lmplatform.common.service.RuntimePolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminRuntimePolicyControllerTest {
    @Test void unauthorizedAndInvalidPoliciesCannotChangeLimits() throws Exception {
        var service=mock(RuntimePolicyService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AdminRuntimePolicyController(service)).addInterceptors(new AdminAuthenticationInterceptor()).build();
        mvc.perform(put("/api/admin/runtime-policy").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        for(String data:new String[]{"{\"defaultApiTokens\":-1,\"maintenanceMessage\":\"maintenance\"}","{\"concurrentRequests\":101,\"maintenanceMessage\":\"maintenance\"}","{\"dailyChatRequests\":-1,\"maintenanceMessage\":\"maintenance\"}","{}"}){
            mvc.perform(put("/api/admin/runtime-policy").sessionAttr(AdminSession.PRINCIPAL_KEY,new AdminPrincipal(1,"admin","Admin","local")).contentType("application/json").content(data)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
