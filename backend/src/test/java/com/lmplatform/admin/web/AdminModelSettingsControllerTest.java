package com.lmplatform.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.common.model.ModelDefaults;
import com.lmplatform.common.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;

/** Verifies administration model defaults are persisted through the database boundary. */
class AdminModelSettingsControllerTest {

    @Test
    void savesDefaultEmbeddingRerankerAndSpeechModels() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        ModelDefaults defaults = new ModelDefaults("embedding", "reranker", "whisper");
        when(repository.saveModelDefaults(defaults)).thenReturn(defaults);

        ModelDefaults saved = new AdminModelSettingsController(repository).saveDefaults(defaults);

        assertThat(saved).isEqualTo(defaults);
        verify(repository).saveModelDefaults(defaults);
    }
}
