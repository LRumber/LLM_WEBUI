package com.lmplatform;

import com.lmplatform.auth.config.OAuth2GatewayProperties;
import com.lmplatform.chat.config.AiServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Application entry point for the platform's core business service. */
@SpringBootApplication
@EnableConfigurationProperties({OAuth2GatewayProperties.class, AiServiceProperties.class})
public class LmPlatformApplication {

    /** Starts Spring Boot and registers all platform modules. */
    public static void main(String[] args) {
        SpringApplication.run(LmPlatformApplication.class, args);
    }
}
