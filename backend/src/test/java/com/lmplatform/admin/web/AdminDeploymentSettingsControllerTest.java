package com.lmplatform.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.common.model.DeploymentSettings;
import com.lmplatform.common.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;

/** Verifies desired host bindings are stored without granting Docker access to the backend. */
class AdminDeploymentSettingsControllerTest {

    @Test
    void savesValidatedDeploymentBindings() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        DeploymentSettings settings = new DeploymentSettings("ai.sitp.ac.cn", 8088, 8443);
        when(repository.saveDeploymentSettings(settings)).thenReturn(settings);

        var response = new AdminDeploymentSettingsController(repository).save(settings);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(settings);
        verify(repository).saveDeploymentSettings(settings);
    }
}
