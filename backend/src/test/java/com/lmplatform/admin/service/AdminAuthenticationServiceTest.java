package com.lmplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.admin.model.LocalAdminCredential;
import com.lmplatform.admin.repository.AdminAccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit tests for local administrator credential verification. */
class AdminAuthenticationServiceTest {

    @Test
    void authenticatesActiveLocalAdministrator() {
        AdminAccountRepository repository = mock(AdminAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(repository.findByUsername("admin")).thenReturn(Optional.of(
                new LocalAdminCredential(4L, "admin", "hash", "系统管理员", (short) 1)
        ));
        when(encoder.matches("secret", "hash")).thenReturn(true);

        var principal = new AdminAuthenticationService(repository, encoder)
                .authenticate(" admin ", "secret");

        assertThat(principal).isPresent();
        assertThat(principal.orElseThrow().source()).isEqualTo("local");
        verify(repository).recordSuccessfulLogin(4L);
    }

    @Test
    void rejectsDisabledAdministrator() {
        AdminAccountRepository repository = mock(AdminAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(repository.findByUsername("admin")).thenReturn(Optional.of(
                new LocalAdminCredential(4L, "admin", "hash", "系统管理员", (short) 0)
        ));

        assertThat(new AdminAuthenticationService(repository, encoder)
                .authenticate("admin", "secret")).isEmpty();
    }
}
