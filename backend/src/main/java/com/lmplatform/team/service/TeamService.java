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
            throw new TeamAccessException("当前不是团队管理员，请联系系统管理员进行添加");
        }
        UUID id = repository.create(userId, request.name().strip(), normalize(request.description()));
        return repository.list(userId).stream().filter(team -> team.id().equals(id)).findFirst()
                .orElseThrow();
    }

    public List<TeamMemberSummary> members(UUID teamId, long userId) {
        List<TeamMemberSummary> members = repository.members(teamId, userId);
        if (members.isEmpty()) throw new TeamAccessException("团队不存在，或当前账号无权访问");
        return members;
    }

    public TeamMemberSummary addMember(
            UUID teamId, long userId, TeamMemberRequest request
    ) {
        if (!repository.canManage(teamId, userId)) {
            throw new TeamAccessException("当前账号没有团队成员管理权限");
        }
        long targetUserId = repository.resolveShadowUser(request.identifier().strip())
                .orElseThrow(() -> new IllegalArgumentException(
                        "未找到该用户，请确认用户已登录过本系统，并输入姓名、工号、账号或用户 ID"
                ));
        repository.addMember(teamId, targetUserId, userId, request.resolvedRole());
        return repository.members(teamId, userId).stream()
                .filter(member -> member.userId() == targetUserId).findFirst().orElseThrow();
    }

    public void removeMember(UUID teamId, long targetUserId, long userId) {
        if (!repository.removeMember(teamId, targetUserId, userId)) {
            throw new TeamAccessException("成员不存在，或不能移除团队所有者");
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
