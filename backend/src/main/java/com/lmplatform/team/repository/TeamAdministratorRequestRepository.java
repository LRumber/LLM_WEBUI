package com.lmplatform.team.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class TeamAdministratorRequestRepository {
    private final JdbcTemplate jdbc;

    public TeamAdministratorRequestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record RequestRow(UUID id, long userId, String displayName, String userNumber,
            String reason, String status, String reviewNote, String reviewer,
            OffsetDateTime createdAt, OffsetDateTime reviewedAt) {}
    public record Status(boolean administrator, RequestRow latest) {}

    public Status status(long userId) {
        boolean administrator = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_team_admin FROM app_user WHERE id = ? AND status = 1", Boolean.class, userId));
        return new Status(administrator, rows("WHERE r.user_id = ? ORDER BY r.created_at DESC LIMIT 1", userId)
                .stream().findFirst().orElse(null));
    }

    public List<RequestRow> list(int page) {
        return rows("ORDER BY CASE r.status WHEN 'pending' THEN 0 ELSE 1 END, r.created_at DESC, r.id LIMIT 20 OFFSET ?",
                (long) Math.max(0, Math.min(page, 1_000_000)) * 20);
    }

    @Transactional
    public UUID submit(long userId, String reason) {
        var users = jdbc.query("SELECT is_team_admin FROM app_user WHERE id = ? AND status = 1 FOR UPDATE",
                (rs, row) -> rs.getBoolean(1), userId);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号不可用");
        if (users.getFirst()) throw new ResponseStatusException(HttpStatus.CONFLICT, "您已是团队管理员");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM team_administrator_request WHERE user_id = ? AND status = 'pending'",
                Long.class, userId) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "您已有待审批的申请，请等待处理");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO team_administrator_request(id, user_id, reason) VALUES (?, ?, ?)", id, userId, reason.strip());
        return id;
    }

    @Transactional
    public void review(UUID id, boolean approved, String note, String actor) {
        var targets = jdbc.query("SELECT user_id FROM team_administrator_request WHERE id = ?",
                (rs, row) -> rs.getLong(1), id);
        if (targets.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "申请不存在");
        long userId = targets.getFirst();
        var users = jdbc.query("SELECT status FROM app_user WHERE id = ? FOR UPDATE", (rs, row) -> rs.getInt(1), userId);
        var states = jdbc.query("SELECT status FROM team_administrator_request WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), id);
        if (!"pending".equals(states.getFirst())) throw new ResponseStatusException(HttpStatus.CONFLICT, "该申请已处理，请刷新列表");
        if (approved) {
            if (users.isEmpty() || users.getFirst() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已停用，无法批准");
            jdbc.update("UPDATE app_user SET is_team_admin = TRUE, updated_at = CURRENT_TIMESTAMP WHERE id = ?", userId);
        }
        jdbc.update("""
                UPDATE team_administrator_request SET status = ?, review_note = ?, reviewer = ?, reviewed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, approved ? "approved" : "rejected", note == null ? "" : note.strip(), actor, id);
    }

    private List<RequestRow> rows(String suffix, Object... args) {
        return jdbc.query("""
                SELECT r.*, u.display_name, u.user_number FROM team_administrator_request r
                JOIN app_user u ON u.id = r.user_id
                """ + suffix, (rs, row) -> new RequestRow(rs.getObject("id", UUID.class), rs.getLong("user_id"),
                rs.getString("display_name"), rs.getString("user_number"), rs.getString("reason"), rs.getString("status"),
                rs.getString("review_note"), rs.getString("reviewer"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("reviewed_at", OffsetDateTime.class)), args);
    }
}
