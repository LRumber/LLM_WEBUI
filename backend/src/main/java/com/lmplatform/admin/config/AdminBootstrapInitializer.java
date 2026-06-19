package com.lmplatform.admin.config;

import com.lmplatform.admin.repository.AdminAccountRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first local administrator only when deployment secrets explicitly provide one. */
@Component
public class AdminBootstrapInitializer implements ApplicationRunner {

    private final AdminBootstrapProperties properties;
    private final AdminAccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapInitializer(
            AdminBootstrapProperties properties,
            AdminAccountRepository repository,
            PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.username() == null || properties.username().isBlank()
                || properties.password() == null || properties.password().isBlank()) {
            return;
        }
        String displayName = properties.displayName() == null || properties.displayName().isBlank()
                ? properties.username().strip()
                : properties.displayName().strip();
        repository.createIfAbsent(
                properties.username().strip(), passwordEncoder.encode(properties.password()), displayName
        );
    }
}
