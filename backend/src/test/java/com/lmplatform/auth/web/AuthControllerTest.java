package com.lmplatform.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.lmplatform.auth.config.OAuth2GatewayProperties;
import com.lmplatform.auth.config.SecurityConfig;
import com.lmplatform.auth.service.OAuth2LoginService;
import com.lmplatform.auth.model.AuthenticatedShadowUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;

/** MVC contract tests for the custom OAuth2 entry point. */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(OAuth2GatewayProperties.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OAuth2LoginService loginService;

    /** Verifies the login route supplies code-flow parameters and an anti-CSRF state value. */
    @Test
    void loginRedirectsToUnifiedIdentityGateway() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uias/oauth/authorize")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("response_type=code")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("state=")));
    }

    /** Prompt-template reads and writes stay available during the temporary development API policy. */
    @Test
    void promptTemplateRoutesPassSecurityWithoutAuthenticationOrCsrf() throws Exception {
        mockMvc.perform(get("/api/prompt-templates"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/prompt-templates").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void callbackSynchronizesShadowUserAndRedirectsToWorkspace() throws Exception {
        when(loginService.authenticate("authorization-code")).thenReturn(
                new AuthenticatedShadowUser(12L, "alice", "Alice", false, true)
        );

        mockMvc.perform(get("/api/auth/oauth2/callback")
                        .param("code", "authorization-code")
                        .param("state", "expected-state")
                        .sessionAttr("oauth2_state", "expected-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/app"));

        verify(loginService).authenticate("authorization-code");
    }
}
