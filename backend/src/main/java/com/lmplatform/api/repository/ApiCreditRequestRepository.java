package com.lmplatform.api.repository;

import com.lmplatform.api.model.ApiPage;
import com.lmplatform.api.service.ApiGatewayException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ApiCreditRequestRepository {
    private final JdbcTemplate jdbc;
    private final ApiCreditRepository credits;

    public ApiCreditRequestRepository(JdbcTemplate jdbc, ApiCreditRepository credits) {
        this.jdbc = jdbc;
        this.credits = credits;
    }

    @Transactional
    public UUID submit(long userId, String type, long amount, String reason) {
        if ((!"tokens".equals(type) && !"unlimited".equals(type)) || amount < 0
                || amount > 9_007_199_254_740_991L || ("tokens".equals(type) && amount == 0)) {
            throw new ApiGatewayException("invalid_request", "请选择申请类型并填写有效的 Token 数量", 400);
        }
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new ApiGatewayException("invalid_request", "请填写申请理由，最多 1000 字", 400);
        }
        var active = jdbc.query("SELECT id FROM app_user WHERE id = ? AND status = 1 FOR UPDATE",
                (rs, row) -> rs.getLong(1), userId);
        if (active.isEmpty()) throw new ApiGatewayException("account_disabled", "当前账号不可用，请联系管理员", 403);
        Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM api_credit_request WHERE user_id = ? AND status = 'pending'", Long.class, userId);
        if (pending != null && pending > 0) throw new ApiGatewayException("request_pending", "您已有待处理的额度申请，请等待管理员审批", 409);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_credit_request(id,user_id,type,requested_tokens,reason) VALUES (?,?,?,?,?)",
                id, userId, type, type.equals("tokens") ? amount : 0, reason.strip());
        return id;
    }

    @Transactional
    public void review(UUID id, boolean approved, long amount, String note, String actor) {
        if (note != null && note.length() > 1000) throw new ApiGatewayException("invalid_request", "处理意见最多 1000 字", 400);
        var rows = jdbc.query("SELECT * FROM api_credit_request WHERE id = ? FOR UPDATE",
                (rs, row) -> new Decision(rs.getLong("user_id"), rs.getString("type"), rs.getString("status")), id);
        if (rows.isEmpty()) throw new ApiGatewayException("request_not_found", "申请不存在", 404);
        var request = rows.getFirst();
        if (!request.status().equals("pending")) throw new ApiGatewayException("request_reviewed", "该申请已经处理，请刷新列表", 409);
        if (approved) {
            if (request.type().equals("tokens") && (amount <= 0 || amount > 9_007_199_254_740_991L)) {
                throw new ApiGatewayException("invalid_request", "批准的 Token 数量必须是大于 0 的有效整数", 400);
            }
            String ledgerNote = "额度申请 " + id + "：" + (note == null ? "" : note.strip());
            credits.adjust(request.userId(), id, request.type().equals("tokens") ? "add" : "unlimited",
                    request.type().equals("tokens") ? amount : 0,
                    ledgerNote.substring(0, Math.min(500, ledgerNote.length())), actor);
        }
        jdbc.update("""
                UPDATE api_credit_request SET status=?, approved_tokens=?, review_note=?, reviewer=?, reviewed_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, approved ? "approved" : "rejected", approved && request.type().equals("tokens") ? amount : 0,
                note == null ? "" : note.strip(), actor, id);
    }

    public ApiPage<RequestRow> list(Long userId, String status, int page, int size) {
        if (!java.util.Set.of("all", "pending", "approved", "rejected").contains(status)) {
            throw new ApiGatewayException("invalid_request", "申请状态无效", 400);
        }
        int p = Math.max(0, Math.min(page, 1_000_000));
        int s = Math.max(1, Math.min(size, 100));
        String where = " WHERE (?::bigint IS NULL OR r.user_id=?) AND (?='all' OR r.status=?)";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM api_credit_request r" + where, Long.class, userId, userId, status, status);
        long pending = jdbc.queryForObject("SELECT COUNT(*) FROM api_credit_request WHERE (?::bigint IS NULL OR user_id=?) AND status='pending'", Long.class, userId, userId);
        var items = jdbc.query("""
                SELECT r.*, u.display_name, u.user_number FROM api_credit_request r JOIN app_user u ON u.id=r.user_id
                """ + where + " ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?", this::map,
                userId, userId, status, status, s, (long) p * s);
        return new ApiPage<>(items, total, p, s, pending);
    }

    private RequestRow map(ResultSet rs, int row) throws SQLException {
        return new RequestRow(rs.getObject("id", UUID.class), rs.getLong("user_id"), rs.getString("display_name"),
                rs.getString("user_number"), rs.getString("type"), rs.getLong("requested_tokens"),
                rs.getObject("approved_tokens", Long.class), rs.getString("reason"), rs.getString("status"),
                rs.getString("review_note"), rs.getString("reviewer"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("reviewed_at", OffsetDateTime.class));
    }

    private record Decision(long userId, String type, String status) {}
    public record RequestRow(UUID id, long userId, String displayName, String userNumber, String type,
            long requestedTokens, Long approvedTokens, String reason, String status, String reviewNote,
            String reviewer, OffsetDateTime createdAt, OffsetDateTime reviewedAt) {}
}
