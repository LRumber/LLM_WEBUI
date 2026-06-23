package com.lmplatform.admin.web;

import com.lmplatform.common.model.ModelDefaults;
import com.lmplatform.common.repository.SystemSettingRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administration API for platform-level model choices stored in the business database. */
@RestController
@RequestMapping("/api/admin/model-settings")
public class AdminModelSettingsController {

    private final SystemSettingRepository repository;

    public AdminModelSettingsController(SystemSettingRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ModelDefaults defaults() {
        return repository.modelDefaults();
    }

    @PutMapping
    public ModelDefaults saveDefaults(@RequestBody ModelDefaults defaults) {
        return repository.saveModelDefaults(defaults);
    }
}
