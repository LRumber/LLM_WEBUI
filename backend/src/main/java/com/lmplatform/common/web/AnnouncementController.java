package com.lmplatform.common.web;

import com.lmplatform.common.model.PlatformAnnouncement;
import com.lmplatform.common.repository.SystemSettingRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public read API for the currently published platform announcement. */
@RestController
@RequestMapping("/api/announcement")
public class AnnouncementController {

    private final SystemSettingRepository repository;

    public AnnouncementController(SystemSettingRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/current")
    public ResponseEntity<PlatformAnnouncement> current() {
        return repository.currentAnnouncement()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
