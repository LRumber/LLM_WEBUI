package com.lmplatform.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lmplatform.common.model.PlatformAnnouncement;
import com.lmplatform.common.repository.AnnouncementRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Verifies only published announcements are exposed to the user app. */
class AnnouncementControllerTest {

    @Test
    void returnsCurrentAnnouncementWhenPublished() {
        AnnouncementRepository repository = mock(AnnouncementRepository.class);
        Instant now = Instant.parse("2026-06-24T02:00:00Z");
        PlatformAnnouncement announcement = new PlatformAnnouncement(
                "4", "V1.0", "Release note", "Knowledge base upgrade", true, now, now, now);
        when(repository.current()).thenReturn(Optional.of(announcement));

        var response = new AnnouncementController(repository).current();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(announcement);
    }

    @Test
    void returnsNoContentWhenNoAnnouncementIsPublished() {
        AnnouncementRepository repository = mock(AnnouncementRepository.class);
        when(repository.current()).thenReturn(Optional.empty());

        var response = new AnnouncementController(repository).current();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void returnsPublishedAnnouncementHistory() {
        AnnouncementRepository repository = mock(AnnouncementRepository.class);
        Instant now = Instant.parse("2026-06-24T02:00:00Z");
        PlatformAnnouncement announcement = new PlatformAnnouncement(
                "5", "V1.1", "Release note", "New features", true, now, now, now);
        when(repository.publishedHistory()).thenReturn(List.of(announcement));

        var response = new AnnouncementController(repository).history();

        assertThat(response).containsExactly(announcement);
    }
}
