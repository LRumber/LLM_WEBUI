package com.lmplatform.api.web;

import com.lmplatform.admin.model.DailyUsageSummary;
import com.lmplatform.api.model.AdminApiOverview;
import com.lmplatform.api.model.AdminApiKeySummary;
import com.lmplatform.api.model.AdminApiUserSummary;
import com.lmplatform.api.model.ApiModelSummary;
import com.lmplatform.api.model.ApiQuotaUpdateRequest;
import com.lmplatform.api.repository.ApiGatewayRepository;
import com.lmplatform.api.service.ApiGatewayService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/api")
public class AdminApiController {

    private final ApiGatewayService service;
    private final ApiGatewayRepository repository;

    public AdminApiController(ApiGatewayService service, ApiGatewayRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @GetMapping("/overview")
    public AdminApiOverview overview() {
        return repository.adminOverview();
    }

    @GetMapping("/usage/daily")
    public List<DailyUsageSummary> dailyUsage(@RequestParam(defaultValue = "7") int days) {
        return repository.adminDailyUsage(Math.max(1, Math.min(days, 30)));
    }

    @GetMapping("/models")
    public List<ApiModelSummary> models() {
        return service.allModelsForAdmin();
    }

    @PutMapping("/models/{modelKey}")
    public ResponseEntity<Void> setModelEnabled(
            @PathVariable String modelKey,
            @RequestBody EnabledRequest request
    ) {
        service.setModelEnabled(modelKey, request.enabled());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users")
    public List<AdminApiUserSummary> users() {
        return repository.adminUsers();
    }

    @PutMapping("/users/{userId}/quota")
    public ResponseEntity<Void> setQuota(
            @PathVariable long userId,
            @Valid @RequestBody ApiQuotaUpdateRequest request
    ) {
        repository.setQuota(userId, request.totalQuota());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/keys")
    public List<AdminApiKeySummary> keys() {
        return repository.allKeys();
    }

    @PatchMapping("/keys/{keyId}")
    public ResponseEntity<Void> setKeyEnabled(
            @PathVariable UUID keyId,
            @RequestBody EnabledRequest request
    ) {
        return repository.setKeyEnabled(keyId, request.enabled())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private record EnabledRequest(boolean enabled) {
    }
}
