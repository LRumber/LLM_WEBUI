package com.lmplatform.admin.web;

import com.lmplatform.common.model.PlatformAnnouncement;
import com.lmplatform.common.model.PlatformAnnouncementRequest;
import com.lmplatform.common.repository.AnnouncementRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Administration API for historical user-facing platform announcements. */
@RestController
@RequestMapping("/api/admin/announcements")
public class AdminAnnouncementController {

    private final AnnouncementRepository repository;

    public AdminAnnouncementController(AnnouncementRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<PlatformAnnouncement> announcements() {
        return repository.all();
    }

    @PostMapping
    public PlatformAnnouncement createAnnouncement(@RequestBody PlatformAnnouncementRequest request) {
        return repository.create(request);
    }

    @PutMapping("/{announcementId}")
    public ResponseEntity<PlatformAnnouncement> updateAnnouncement(
            @PathVariable UUID announcementId,
            @RequestBody PlatformAnnouncementRequest request
    ) {
        repository.findById(announcementId)
                .filter(PlatformAnnouncement::published)
                .ifPresent(announcement -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Published announcements cannot be edited");
                });
        return repository.updateDraft(announcementId, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
