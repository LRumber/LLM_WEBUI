package com.lmplatform.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.common.model.PlatformAnnouncement;
import com.lmplatform.common.model.PlatformAnnouncementRequest;
import com.lmplatform.common.repository.AnnouncementRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Verifies administrators can manage historical user-facing announcements. */
class AdminAnnouncementControllerTest {

    @Test
    void listsAnnouncements() {
        AnnouncementRepository repository = mock(AnnouncementRepository.class);
        PlatformAnnouncement announcement = announcement("10000000-0000-0000-0000-000000000001");
        when(repository.all()).thenReturn(List.of(announcement));

        List<PlatformAnnouncement> announcements = new AdminAnnouncementController(repository).announcements();

        assertThat(announcements).containsExactly(announcement);
    }

    @Test
    void createsAnnouncement() {
        AnnouncementRepository repository = mock(AnnouncementRepository.class);
        PlatformAnnouncementRequest request = new PlatformAnnouncementRequest("V1.0", "New model", "DeepSeek R1 released", true);
        PlatformAnnouncement announcement = announcement("10000000-0000-0000-0000-000000000002");
        when(repository.create(request)).thenReturn(announcement);

        PlatformAnnouncement saved = new AdminAnnouncementController(repository).createAnnouncement(request);

        assertThat(saved).isEqualTo(announcement);
        verify(repository).create(request);
    }

    @Test
    void updatesDraftAnnouncement() {
        AnnouncementRepository repository = mock(AnnouncementRepository.class);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000003");
        PlatformAnnouncementRequest request = new PlatformAnnouncementRequest("V1.1", "Draft update", "Knowledge base upgrade", false);
        PlatformAnnouncement announcement = announcement(id.toString(), false);
        when(repository.findById(id)).thenReturn(Optional.of(announcement));
        when(repository.updateDraft(id, request)).thenReturn(Optional.of(announcement));

        var response = new AdminAnnouncementController(repository).updateAnnouncement(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(announcement);
    }

    @Test
    void rejectsPublishedAnnouncementUpdates() {
        AnnouncementRepository repository = mock(AnnouncementRepository.class);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000004");
        PlatformAnnouncementRequest request = new PlatformAnnouncementRequest("V1.2", "Published update", "Should be blocked", true);
        when(repository.findById(id)).thenReturn(Optional.of(announcement(id.toString(), true)));

        try {
            new AdminAnnouncementController(repository).updateAnnouncement(id, request);
        } catch (ResponseStatusException error) {
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            return;
        }

        throw new AssertionError("Expected published announcement update to be rejected");
    }

    private PlatformAnnouncement announcement(String id) {
        return announcement(id, true);
    }

    private PlatformAnnouncement announcement(String id, boolean published) {
        Instant now = Instant.parse("2026-06-24T02:00:00Z");
        return new PlatformAnnouncement(id, "V1.0", "Announcement", "Content", published, now, now, published ? now : null);
    }
}
