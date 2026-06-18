package com.lmplatform.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/actuator/health", "/api/chat/**"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/health", "/api/auth/login", "/api/auth/oauth2/callback", "/actuator/health").permitAll()
                        .requestMatchers("/api/models", "/api/chat/**", "/api/conversations/**").permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
