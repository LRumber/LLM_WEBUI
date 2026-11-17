package com.lmplatform.team.web;

import com.lmplatform.admin.model.AdminPrincipal;
import com.lmplatform.admin.web.AdminSession;
import com.lmplatform.team.repository.TeamAdministratorRequestRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/team-administrator-requests")
public class AdminTeamAdministratorRequestController {
    private final TeamAdministratorRequestRepository requests;

    public AdminTeamAdministratorRequestController(TeamAdministratorRequestRepository requests) { this.requests = requests; }

    @GetMapping
    public List<TeamAdministratorRequestRepository.RequestRow> list(@RequestParam(defaultValue = "0") int page) {
        return requests.list(page);
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<Void> review(@PathVariable UUID id, @Valid @RequestBody Decision decision, HttpSession session) {
        var admin = (AdminPrincipal) session.getAttribute(AdminSession.PRINCIPAL_KEY);
        requests.review(id, decision.approved(), decision.note(), admin.source() + ":" + admin.id() + ":" + admin.displayName());
        return ResponseEntity.noContent().build();
    }

    public record Decision(@NotNull Boolean approved, @Size(max = 1000) String note) {}
}
