package com.lmplatform.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lmplatform.common.model.PlatformAnnouncement;
import com.lmplatform.common.repository.SystemSettingRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Verifies only a published current announcement is exposed to the user app. */
class AnnouncementControllerTest {

    @Test
    void returnsCurrentAnnouncementWhenPublished() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        PlatformAnnouncement announcement = new PlatformAnnouncement("4", "功能上新", "知识库检索升级", true);
        when(repository.currentAnnouncement()).thenReturn(Optional.of(announcement));

        var response = new AnnouncementController(repository).current();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(announcement);
    }

    @Test
    void returnsNoContentWhenNoAnnouncementIsPublished() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.currentAnnouncement()).thenReturn(Optional.empty());

        var response = new AnnouncementController(repository).current();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }
}
