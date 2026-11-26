package com.lmplatform.common.web;

import com.lmplatform.common.model.PlatformSettings;
import com.lmplatform.common.service.PlatformSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformSettingsController {
    private final PlatformSettingsService service;
    public PlatformSettingsController(PlatformSettingsService service) { this.service = service; }
    @GetMapping("/api/platform/settings")
    public PlatformSettings settings() { return service.current(); }
}
