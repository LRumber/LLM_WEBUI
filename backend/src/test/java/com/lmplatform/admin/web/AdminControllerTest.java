package com.lmplatform.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.admin.model.AdminOverview;
import com.lmplatform.admin.repository.AdminRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for administration API delegation and query boundaries. */
class AdminControllerTest {

    @Test
    void returnsOverviewFromRepository() {
        AdminRepository repository = mock(AdminRepository.class);
        AdminOverview overview = new AdminOverview(3, 8, 5, 1200);
        when(repository.overview()).thenReturn(overview);

        assertThat(new AdminController(repository).overview()).isEqualTo(overview);
    }

    @Test
    void limitsUsageWindowToThirtyDays() {
        AdminRepository repository = mock(AdminRepository.class);
        when(repository.dailyUsage(30)).thenReturn(List.of());

        assertThat(new AdminController(repository).dailyUsage(365)).isEmpty();
        verify(repository).dailyUsage(30);
    }

    @Test
    void grantsOAuthUserAdministratorAccess() {
        AdminRepository repository = mock(AdminRepository.class);
        when(repository.setUserAdministrator(9L, true)).thenReturn(true);

        assertThat(new AdminController(repository).setUserAdministrator(
                9L, new AdministratorStatusRequest(true)
        ).getStatusCode().value()).isEqualTo(204);
        verify(repository).setUserAdministrator(9L, true);
    }
}
