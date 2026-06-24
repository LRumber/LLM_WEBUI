package com.lmplatform.admin.web;

import com.lmplatform.common.model.PlatformAnnouncement;
import com.lmplatform.common.model.PlatformAnnouncementRequest;
import com.lmplatform.common.repository.SystemSettingRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administration API for the user-facing product announcement modal. */
@RestController
@RequestMapping("/api/admin/announcement")
public class AdminAnnouncementController {

    private final SystemSettingRepository repository;

    public AdminAnnouncementController(SystemSettingRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public PlatformAnnouncement announcement() {
        return repository.announcement();
    }

    @PutMapping
    public PlatformAnnouncement saveAnnouncement(@RequestBody PlatformAnnouncementRequest request) {
        return repository.saveAnnouncement(request);
    }
}
