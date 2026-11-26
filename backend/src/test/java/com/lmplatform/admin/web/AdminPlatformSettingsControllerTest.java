package com.lmplatform.admin.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lmplatform.admin.config.AdminAuthenticationInterceptor;
import com.lmplatform.admin.model.AdminPrincipal;
import com.lmplatform.common.service.PlatformSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminPlatformSettingsControllerTest {
    @Test
    void requiresAdminAndValidatesBrandingBeforeWriting() throws Exception {
        var service = mock(PlatformSettingsService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AdminPlatformSettingsController(service))
                .addInterceptors(new AdminAuthenticationInterceptor()).build();
        mvc.perform(get("/api/admin/platform-settings")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/platform-settings").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/platform-settings")
                .sessionAttr(AdminSession.PRINCIPAL_KEY, new AdminPrincipal(1L, "admin", "管理员", "local"))
                .contentType("application/json").content("{\"platformName\":\" \",\"loginDescription\":\"说明\",\"maxUploadBytes\":1024,\"allowedExtensions\":\".pdf\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
