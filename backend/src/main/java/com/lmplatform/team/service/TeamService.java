package com.lmplatform.team.service;

import com.lmplatform.team.model.TeamMemberRequest;
import com.lmplatform.team.model.TeamMemberSummary;
import com.lmplatform.team.model.TeamRequest;
import com.lmplatform.team.model.TeamSummary;
import com.lmplatform.team.repository.TeamRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Enforces team-administrator creation rights and owner/admin membership management. */
@Service
public class TeamService {

    private final TeamRepository repository;

    public TeamService(TeamRepository repository) {
        this.repository = repository;
    }

    public List<TeamSummary> list(long userId) {
        return repository.list(userId);
    }

    public TeamSummary create(long userId, TeamRequest request) {
        if (!repository.isTeamAdministrator(userId)) {
            throw new TeamAccessException("Only a designated team administrator can create a team");
        }
        UUID id = repository.create(userId, request.name().strip(), normalize(request.description()));
        return repository.list(userId).stream().filter(team -> team.id().equals(id)).findFirst()
                .orElseThrow();
    }

    public List<TeamMemberSummary> members(UUID teamId, long userId) {
        List<TeamMemberSummary> members = repository.members(teamId, userId);
        if (members.isEmpty()) throw new TeamAccessException("Team not found");
        return members;
    }

    public TeamMemberSummary addMember(
            UUID teamId, long userId, TeamMemberRequest request
    ) {
        if (!repository.canManage(teamId, userId)) {
            throw new TeamAccessException("Team management permission is required");
        }
        long targetUserId = repository.resolveShadowUser(request.identifier().strip())
                .orElseThrow(() -> new IllegalArgumentException(
                        "User has not signed in through OAuth2 and no shadow user exists"
                ));
        repository.addMember(teamId, targetUserId, userId, request.resolvedRole());
        return repository.members(teamId, userId).stream()
                .filter(member -> member.userId() == targetUserId).findFirst().orElseThrow();
    }

    public void removeMember(UUID teamId, long targetUserId, long userId) {
        if (!repository.removeMember(teamId, targetUserId, userId)) {
            throw new TeamAccessException("Member not found or cannot remove the team owner");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public static class TeamAccessException extends RuntimeException {
        public TeamAccessException(String message) {
            super(message);
        }
    }
}
