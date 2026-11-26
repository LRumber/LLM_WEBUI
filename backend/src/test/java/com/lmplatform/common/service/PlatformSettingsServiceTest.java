package com.lmplatform.common.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.common.model.PlatformSettings;
import com.lmplatform.common.repository.SystemSettingRepository;
import com.lmplatform.file.config.ObjectStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

class PlatformSettingsServiceTest {
    private final SystemSettingRepository repository = mock(SystemSettingRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private PlatformSettingsService service() {
        var multipart = new MultipartProperties();
        multipart.setMaxFileSize(DataSize.ofMegabytes(2));
        return new PlatformSettingsService(repository, mapper,
                new ObjectStorageProperties("", "", "", "", 4 * 1048576), multipart);
    }

    @Test
    void defaultsRespectActualDeploymentCeiling() {
        assertThat(service().uploadCeiling()).isEqualTo(2 * 1048576);
        assertThat(service().current().maxUploadBytes()).isEqualTo(2 * 1048576);
    }

    @Test
    void savedPolicyImmediatelyControlsNewUploadsAndTracksLoweredDeploymentCeiling() throws Exception {
        doAnswer(invocation -> {
            when(repository.platformSettingsJson()).thenReturn(invocation.getArgument(0));
            return null;
        }).when(repository).savePlatformSettingsJson(anyString());
        var service = service();
        var saved = service.save(new PlatformSettings(" 平台 ", " 登录说明 ", " 联系人 ", 1024, ".PDF, .txt,.pdf"));
        assertThat(saved.allowedExtensions()).isEqualTo(".pdf,.txt");
        assertThat(service.current().platformName()).isEqualTo("平台");
        service.validateUpload("guide.PDF", 1024);
        assertThatThrownBy(() -> service.validateUpload("image.png", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateUpload("guide.pdf", 1025)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateUpload("guide.pdf", 0)).isInstanceOf(IllegalArgumentException.class);
        service.save(new PlatformSettings("平台", "说明", "", 2 * 1048576, ".pdf"));
        var smaller = new MultipartProperties(); smaller.setMaxFileSize(DataSize.ofKilobytes(512));
        var restarted = new PlatformSettingsService(repository, mapper,
                new ObjectStorageProperties("", "", "", "", 4 * 1048576), smaller);
        assertThat(restarted.current().maxUploadBytes()).isEqualTo(512 * 1024);
    }

    @Test
    void rejectsImpossibleSizeAndUnsupportedTypesWithoutSaving() {
        var service = service();
        for (long bytes : new long[]{0, -1, 3 * 1048576}) {
            assertThatThrownBy(() -> service.save(new PlatformSettings("平台", "说明", "", bytes, ".pdf")))
                    .isInstanceOf(ResponseStatusException.class);
        }
        for (String types : new String[]{".exe", "*", ".pdf,", ""}) {
            assertThatThrownBy(() -> service.save(new PlatformSettings("平台", "说明", "", 1024, types)))
                    .isInstanceOf(ResponseStatusException.class);
        }
        verify(repository, never()).savePlatformSettingsJson(anyString());
    }
}
