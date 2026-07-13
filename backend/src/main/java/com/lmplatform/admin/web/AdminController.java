package com.lmplatform.admin.web;

import com.lmplatform.admin.model.AdminOverview;
import com.lmplatform.admin.model.AdminUserPage;
import com.lmplatform.admin.model.DailyUsageSummary;
import com.lmplatform.admin.repository.AdminRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only APIs for the first administration dashboard iteration. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminRepository repository;

    public AdminController(AdminRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/overview")
    public AdminOverview overview() {
        return repository.overview();
    }

    @GetMapping("/usage/daily")
    public List<DailyUsageSummary> dailyUsage(@RequestParam(defaultValue = "7") int days) {
        return repository.dailyUsage(Math.max(1, Math.min(days, 30)));
    }

    @GetMapping("/users")
    public AdminUserPage users(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return repository.users(q, Math.max(0, page), Math.max(10, Math.min(size, 100)));
    }

    @PatchMapping("/users/{userId}/administrator")
    public ResponseEntity<Void> setUserAdministrator(
            @PathVariable long userId,
            @RequestBody AdministratorStatusRequest request
    ) {
        return repository.setUserAdministrator(userId, request.enabled())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PatchMapping("/users/{userId}/team-administrator")
    public ResponseEntity<Void> setTeamAdministrator(
            @PathVariable long userId,
            @RequestBody AdministratorStatusRequest request
    ) {
        return repository.setTeamAdministrator(userId, request.enabled())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
