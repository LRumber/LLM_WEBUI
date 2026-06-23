package com.lmplatform.admin.web;

import com.lmplatform.common.model.DeploymentSettings;
import com.lmplatform.common.repository.SystemSettingRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stores desired public bindings; a host-side script applies them to Docker safely. */
@RestController
@RequestMapping("/api/admin/deployment-settings")
public class AdminDeploymentSettingsController {

    private final SystemSettingRepository repository;

    public AdminDeploymentSettingsController(SystemSettingRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public DeploymentSettings settings() {
        return repository.deploymentSettings();
    }

    @PutMapping
    public ResponseEntity<?> save(@RequestBody DeploymentSettings settings) {
        try {
            return ResponseEntity.ok(repository.saveDeploymentSettings(settings));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(new SettingError(exception.getMessage()));
        }
    }

    private record SettingError(String message) {
    }
}
