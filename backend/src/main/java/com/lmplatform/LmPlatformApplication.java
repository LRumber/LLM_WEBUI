package com.lmplatform;

import com.lmplatform.auth.config.OAuth2GatewayProperties;
import com.lmplatform.chat.config.AiServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({OAuth2GatewayProperties.class, AiServiceProperties.class})
public class LmPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(LmPlatformApplication.class, args);
    }
}
