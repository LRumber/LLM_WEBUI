package com.lmplatform.admin.service;

import com.lmplatform.admin.model.AdminPrincipal;
import com.lmplatform.admin.model.LocalAdminCredential;
import com.lmplatform.admin.repository.AdminAccountRepository;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Authenticates local administrator credentials without consulting OAuth2 identities. */
@Service
public class AdminAuthenticationService {

    private final AdminAccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AdminAuthenticationService(AdminAccountRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<AdminPrincipal> authenticate(String username, String password) {
        Optional<LocalAdminCredential> credential = repository.findByUsername(username.strip());
        if (credential.isEmpty()) {
            return Optional.empty();
        }
        LocalAdminCredential account = credential.get();
        if (account.status() != 1 || !passwordEncoder.matches(password, account.passwordHash())) {
            return Optional.empty();
        }
        repository.recordSuccessfulLogin(account.id());
        return Optional.of(new AdminPrincipal(
                account.id(), account.username(), account.displayName(), "local"
        ));
    }
}
