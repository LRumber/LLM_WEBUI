package com.lmplatform.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lmplatform.auth.config.OAuth2GatewayProperties;
import com.lmplatform.auth.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(OAuth2GatewayProperties.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginRedirectsToUnifiedIdentityGateway() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/uias/oauth/authorize")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("response_type=code")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("state=")));
    }
}
