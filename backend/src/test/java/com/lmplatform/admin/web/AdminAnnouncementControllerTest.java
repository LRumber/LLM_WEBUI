package com.lmplatform.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.common.model.PlatformAnnouncement;
import com.lmplatform.common.model.PlatformAnnouncementRequest;
import com.lmplatform.common.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;

/** Verifies administrators can save the user-facing announcement through the settings store. */
class AdminAnnouncementControllerTest {

    @Test
    void savesAnnouncement() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        PlatformAnnouncementRequest request = new PlatformAnnouncementRequest("新模型上线", "DeepSeek R1 已发布", true);
        PlatformAnnouncement announcement = new PlatformAnnouncement("3", "新模型上线", "DeepSeek R1 已发布", true);
        when(repository.saveAnnouncement(request)).thenReturn(announcement);

        PlatformAnnouncement saved = new AdminAnnouncementController(repository).saveAnnouncement(request);

        assertThat(saved).isEqualTo(announcement);
        verify(repository).saveAnnouncement(request);
    }
}
