package com.lmplatform.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lmplatform.admin.model.AdminPrincipal;
import com.lmplatform.admin.service.AdminAuthenticationService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Unit tests for administrator session lifetime behavior. */
class AdminAuthControllerTest {

    @Test
    void loginKeepsSessionAcrossRefreshUntilOneHourOfInactivity() {
        AdminAuthenticationService service = mock(AdminAuthenticationService.class);
        AdminPrincipal principal = new AdminPrincipal(1L, "admin", "System Admin", "local");
        when(service.authenticate("admin", "secret")).thenReturn(Optional.of(principal));
        MockHttpServletRequest request = new MockHttpServletRequest();

        var response = new AdminAuthController(service).login(
                new AdminLoginRequest("admin", "secret"),
                request
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(request.getSession(false).getAttribute(AdminSession.PRINCIPAL_KEY)).isEqualTo(principal);
        assertThat(request.getSession(false).getMaxInactiveInterval())
                .isEqualTo(AdminSession.MAX_INACTIVE_SECONDS);
    }
}
