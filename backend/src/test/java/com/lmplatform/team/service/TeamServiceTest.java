package com.lmplatform.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.team.model.TeamMemberRequest;
import com.lmplatform.team.model.TeamMemberSummary;
import com.lmplatform.team.model.TeamRequest;
import com.lmplatform.team.model.TeamSummary;
import com.lmplatform.team.repository.TeamRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tests team-administrator creation and shadow-user membership rules. */
class TeamServiceTest {

    @Test
    void designatedTeamAdministratorCreatesOwnedTeam() {
        TeamRepository repository = mock(TeamRepository.class);
        UUID teamId = UUID.randomUUID();
        TeamSummary team = new TeamSummary(teamId, "研发团队", null, "owner", 1, OffsetDateTime.now());
        when(repository.isTeamAdministrator(9L)).thenReturn(true);
        when(repository.create(9L, "研发团队", null)).thenReturn(teamId);
        when(repository.list(9L)).thenReturn(List.of(team));

        assertThat(new TeamService(repository).create(9L, new TeamRequest(" 研发团队 ", null)))
                .isEqualTo(team);
    }

    @Test
    void rejectsUnknownOAuthShadowUser() {
        TeamRepository repository = mock(TeamRepository.class);
        UUID teamId = UUID.randomUUID();
        when(repository.canManage(teamId, 9L)).thenReturn(true);
        when(repository.resolveShadowUser("missing-user")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new TeamService(repository).addMember(
                teamId, 9L, new TeamMemberRequest("missing-user", "member")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addsResolvedShadowUserByAccountOrId() {
        TeamRepository repository = mock(TeamRepository.class);
        UUID teamId = UUID.randomUUID();
        TeamMemberSummary member = new TeamMemberSummary(12L, "alice", "U12", "Alice", "member");
        when(repository.canManage(teamId, 9L)).thenReturn(true);
        when(repository.resolveShadowUser("alice")).thenReturn(Optional.of(12L));
        when(repository.members(teamId, 9L)).thenReturn(List.of(member));

        assertThat(new TeamService(repository).addMember(
                teamId, 9L, new TeamMemberRequest("alice", null)
        )).isEqualTo(member);
        verify(repository).addMember(teamId, 12L, 9L, "member");
    }
}
