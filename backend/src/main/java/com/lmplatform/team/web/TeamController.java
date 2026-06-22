package com.lmplatform.team.web;

import com.lmplatform.chat.repository.ConversationRepository;
import com.lmplatform.team.model.TeamMemberRequest;
import com.lmplatform.team.model.TeamMemberSummary;
import com.lmplatform.team.model.TeamRequest;
import com.lmplatform.team.model.TeamSummary;
import com.lmplatform.team.service.TeamService;
import com.lmplatform.knowledge.service.KnowledgeBaseService;
import com.lmplatform.knowledge.model.KnowledgeBaseRequest;
import com.lmplatform.knowledge.model.KnowledgeBaseSummary;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Frontend team management API backed only by existing OAuth2 shadow users. */
@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamService service;
    private final ConversationRepository users;
    private final KnowledgeBaseService knowledgeBaseService;

    public TeamController(
            TeamService service,
            ConversationRepository users,
            KnowledgeBaseService knowledgeBaseService
    ) {
        this.service = service;
        this.users = users;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping
    public List<TeamSummary> teams() {
        return service.list(users.ensureDevelopmentUser());
    }

    @PostMapping
    public ResponseEntity<TeamSummary> create(@Valid @RequestBody TeamRequest request) {
        TeamSummary team = service.create(users.ensureDevelopmentUser(), request);
        return ResponseEntity.created(URI.create("/api/teams/" + team.id())).body(team);
    }

    @GetMapping("/{teamId}/members")
    public List<TeamMemberSummary> members(@PathVariable UUID teamId) {
        return service.members(teamId, users.ensureDevelopmentUser());
    }

    @PostMapping("/{teamId}/members")
    public TeamMemberSummary addMember(
            @PathVariable UUID teamId,
            @Valid @RequestBody TeamMemberRequest request
    ) {
        return service.addMember(teamId, users.ensureDevelopmentUser(), request);
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID teamId,
            @PathVariable long userId
    ) {
        service.removeMember(teamId, userId, users.ensureDevelopmentUser());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{teamId}/knowledge-bases")
    public ResponseEntity<KnowledgeBaseSummary> createKnowledgeBase(
            @PathVariable UUID teamId,
            @Valid @RequestBody KnowledgeBaseRequest request
    ) {
        KnowledgeBaseSummary knowledgeBase = knowledgeBaseService.createTeam(
                users.ensureDevelopmentUser(), teamId, request
        );
        return ResponseEntity.created(URI.create(
                "/api/knowledge-bases/" + knowledgeBase.id()
        )).body(knowledgeBase);
    }
}
