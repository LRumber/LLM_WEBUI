package com.lmplatform.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/** Defines public authentication routes and the temporary development API policy. */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Builds the HTTP security chain.
     * Session-bound browser APIs use same-site cookies and explicit MVC authorization interceptors.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/actuator/health", "/api/auth/**", "/api/chat/**", "/api/conversations/**",
                        "/api/files/**", "/api/speech/**", "/api/prompt-templates/**", "/api/admin/**",
                        "/api/knowledge-bases/**", "/api/teams/**", "/api/announcement/**"
                ))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/health", "/api/auth/**", "/actuator/health").permitAll()
                        .requestMatchers(
                                "/api/models", "/api/chat/**", "/api/conversations/**",
                                "/api/files/**", "/api/speech/**", "/api/prompt-templates/**", "/api/admin/**",
                                "/api/knowledge-bases/**", "/api/teams/**", "/api/announcement/**"
                        ).permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
