package com.lmplatform.common.web;

import com.lmplatform.common.model.PlatformAnnouncement;
import com.lmplatform.common.repository.AnnouncementRepository;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public read API for the currently published platform announcement. */
@RestController
@RequestMapping("/api/announcement")
public class AnnouncementController {

    private final AnnouncementRepository repository;

    public AnnouncementController(AnnouncementRepository repository) {
        this.repository = repository;
    }

    @GetMapping(value = "/current", produces = "application/json;charset=UTF-8")
    public ResponseEntity<PlatformAnnouncement> current() {
        return repository.current()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping(value = "/history", produces = "application/json;charset=UTF-8")
    public List<PlatformAnnouncement> history() {
        return repository.publishedHistory();
    }
}
