package com.lmplatform.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.lmplatform.auth.config.OAuth2GatewayProperties;
import com.lmplatform.auth.config.SecurityConfig;
import com.lmplatform.auth.repository.ShadowUserRepository;
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

    @MockBean
    private ShadowUserRepository shadowUserRepository;

    /** Verifies the login route supplies the provider-compatible code-flow parameters. */
    @Test
    void loginRedirectsToUnifiedIdentityGateway() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uias/oauth/authorize")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("response_type=code")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("state="))));
    }

    /** Private workspace routes reject browsers that have not completed OAuth2 login. */
    @Test
    void promptTemplateRoutesRequireUserSession() throws Exception {
        mockMvc.perform(get("/api/prompt-templates"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/prompt-templates").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callbackSynchronizesShadowUserAndRedirectsToWorkspace() throws Exception {
        when(loginService.authenticate("authorization-code")).thenReturn(
                new AuthenticatedShadowUser(12L, 5901L, "210323199", "alice", "Alice", "6559", "alice@example.test", false, true)
        );

        mockMvc.perform(get("/api/auth/oauth2/callback")
                        .param("code", "authorization-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/app"));

        verify(loginService).authenticate("authorization-code");
    }

    @Test
    void returnsSynchronizedUserFromSession() throws Exception {
        var user = new AuthenticatedShadowUser(12L, 5901L, "210323199", "alice", "Alice", "6559", "alice@example.test", false, true);
        mockMvc.perform(get("/api/auth/me").sessionAttr(UserSession.USER_KEY, user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idNo").value("210323199"))
                .andExpect(jsonPath("$.accountNumber").value("alice"))
                .andExpect(jsonPath("$.userNumber").value("6559"))
                .andExpect(jsonPath("$.displayName").value("Alice"));
    }

    @Test
    void devLoginIsDisabledByDefault() throws Exception {
        mockMvc.perform(get("/api/auth/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devLoginEnabled").value(false));

        mockMvc.perform(post("/api/auth/dev-login"))
                .andExpect(status().isNotFound());
    }
}
