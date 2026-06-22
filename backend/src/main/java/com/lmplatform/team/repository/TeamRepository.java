package com.lmplatform.team.repository;

import com.lmplatform.team.model.TeamMemberSummary;
import com.lmplatform.team.model.TeamSummary;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persists teams and resolves members exclusively from previously synchronized shadow users. */
@Repository
public class TeamRepository {

    private final JdbcTemplate jdbc;

    public TeamRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isTeamAdministrator(long userId) {
        Boolean enabled = jdbc.queryForObject(
                "SELECT is_team_admin FROM app_user WHERE id = ? AND status = 1",
                Boolean.class, userId
        );
        return Boolean.TRUE.equals(enabled);
    }

    @Transactional
    public UUID create(long userId, String name, String description) {
        UUID teamId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO team_space (id, name, description, owner_user_id)
                VALUES (?, ?, ?, ?)
                """, teamId, name, description, userId);
        jdbc.update("""
                INSERT INTO team_member (team_id, user_id, member_role, added_by_user_id)
                VALUES (?, ?, 'owner', ?)
                """, teamId, userId, userId);
        return teamId;
    }

    public List<TeamSummary> list(long userId) {
        return jdbc.query("""
                SELECT ts.id, ts.name, ts.description, tm.member_role,
                       (SELECT COUNT(*) FROM team_member members WHERE members.team_id = ts.id) AS member_count,
                       ts.created_at
                FROM team_space ts
                JOIN team_member tm ON tm.team_id = ts.id AND tm.user_id = ?
                WHERE ts.status = 'active'
                ORDER BY ts.updated_at DESC
                """, (rs, rowNum) -> new TeamSummary(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("description"), rs.getString("member_role"),
                rs.getInt("member_count"), rs.getObject("created_at", OffsetDateTime.class)
        ), userId);
    }

    public List<TeamMemberSummary> members(UUID teamId, long userId) {
        return jdbc.query("""
                SELECT au.id, au.account_number, au.user_number, au.display_name, member.member_role
                FROM team_member member
                JOIN team_member caller ON caller.team_id = member.team_id AND caller.user_id = ?
                JOIN app_user au ON au.id = member.user_id
                WHERE member.team_id = ?
                ORDER BY CASE member.member_role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END,
                         au.display_name
                """, (rs, rowNum) -> new TeamMemberSummary(
                rs.getLong("id"), rs.getString("account_number"), rs.getString("user_number"),
                rs.getString("display_name"), rs.getString("member_role")
        ), userId, teamId);
    }

    /** Resolves an identity only from active local shadow users; unknown OAuth accounts remain unavailable. */
    public Optional<Long> resolveShadowUser(String identifier) {
        Long numericId = null;
        try {
            numericId = Long.parseLong(identifier);
        } catch (NumberFormatException ignored) {
            // Account and user-number matching below handles nonnumeric identifiers.
        }
        return jdbc.query("""
                SELECT id FROM app_user
                WHERE status = 1 AND (id = ? OR account_number = ? OR user_number = ?)
                LIMIT 1
                """, (rs, rowNum) -> rs.getLong("id"), numericId, identifier, identifier)
                .stream().findFirst();
    }

    public boolean canManage(UUID teamId, long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM team_member
                WHERE team_id = ? AND user_id = ? AND member_role IN ('owner', 'admin')
                """, Integer.class, teamId, userId);
        return count != null && count > 0;
    }

    @Transactional
    public void addMember(UUID teamId, long targetUserId, long addedBy, String role) {
        jdbc.update("""
                INSERT INTO team_member (team_id, user_id, member_role, added_by_user_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (team_id, user_id) DO UPDATE SET member_role = EXCLUDED.member_role
                """, teamId, targetUserId, role, addedBy);
        jdbc.update("""
                INSERT INTO knowledge_member (
                    knowledge_base_id, user_id, member_role, permission_source
                ) SELECT kb.id, ?, CASE ? WHEN 'admin' THEN 'admin' ELSE 'viewer' END, 'team'
                    FROM knowledge_base kb
                   WHERE kb.space_type = 'team' AND kb.scope_ref = ? AND kb.status = 'active'
                ON CONFLICT (knowledge_base_id, user_id) DO UPDATE SET
                    member_role = EXCLUDED.member_role
                WHERE knowledge_member.permission_source = 'team'
                """, targetUserId, role, teamId);
    }

    @Transactional
    public boolean removeMember(UUID teamId, long targetUserId, long removedBy) {
        boolean removed = jdbc.update("""
                DELETE FROM team_member target USING team_member caller
                WHERE target.team_id = ? AND target.user_id = ? AND target.member_role <> 'owner'
                  AND caller.team_id = target.team_id AND caller.user_id = ?
                  AND caller.member_role IN ('owner', 'admin')
                """, teamId, targetUserId, removedBy) == 1;
        if (removed) {
            jdbc.update("""
                    DELETE FROM knowledge_member km USING knowledge_base kb
                    WHERE km.knowledge_base_id = kb.id AND km.user_id = ?
                      AND km.permission_source = 'team'
                      AND kb.space_type = 'team' AND kb.scope_ref = ?
                    """, targetUserId, teamId);
        }
        return removed;
    }
}
