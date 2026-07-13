package com.lmplatform.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Applies OAuth2 user-session authorization to every private workspace API. */
@Configuration
public class UserWebConfig implements WebMvcConfigurer {

    private final UserAuthenticationInterceptor interceptor;

    public UserWebConfig(UserAuthenticationInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(
                "/api/chat/**",
                "/api/conversations/**",
                "/api/files/**",
                "/api/speech/**",
                "/api/prompt-templates/**",
                "/api/knowledge-bases/**",
                "/api/teams/**",
                "/api/developer/**"
        );
    }
}
