package com.lmplatform.team.web;

import com.lmplatform.chat.repository.ConversationRepository;
import com.lmplatform.team.repository.TeamAdministratorRequestRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teams/administrator-request")
public class TeamAdministratorRequestController {
    private final TeamAdministratorRequestRepository requests;
    private final ConversationRepository users;

    public TeamAdministratorRequestController(TeamAdministratorRequestRepository requests, ConversationRepository users) {
        this.requests = requests;
        this.users = users;
    }

    @GetMapping
    public TeamAdministratorRequestRepository.Status status() { return requests.status(users.ensureDevelopmentUser()); }

    @PostMapping
    public Map<String, UUID> submit(@Valid @RequestBody Application request) {
        return Map.of("id", requests.submit(users.ensureDevelopmentUser(), request.reason()));
    }

    public record Application(@NotBlank @Size(max = 1000) String reason) {}
}
