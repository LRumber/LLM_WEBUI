package com.lmplatform.admin.web;

import com.lmplatform.common.model.PlatformSettings;
import com.lmplatform.common.service.PlatformSettingsService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/admin/platform-settings")
public class AdminPlatformSettingsController {
    private final PlatformSettingsService service;
    public AdminPlatformSettingsController(PlatformSettingsService service) { this.service = service; }
    @GetMapping
    public Map<String, Object> settings() {
        return Map.of("settings", service.current(), "uploadCeiling", service.uploadCeiling(),
                "supportedExtensions", PlatformSettingsService.EXTENSIONS);
    }
    @PutMapping
    public PlatformSettings save(@Valid @RequestBody PlatformSettings settings) { return service.save(settings); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> invalid(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", ex.getReason()));
    }
}
