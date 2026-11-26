package com.lmplatform.api.repository;

import com.lmplatform.api.service.ApiGatewayException;
import com.lmplatform.api.service.InsufficientApiCreditException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Serializes grants, deductions and reservations on the user's quota row. */
@Repository
public class ApiCreditRepository {
    private final JdbcTemplate jdbc;

    public ApiCreditRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private void lock(long userId) {
        jdbc.update("INSERT INTO api_user_quota(user_id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        jdbc.queryForObject("SELECT user_id FROM api_user_quota WHERE user_id = ? FOR UPDATE", Long.class, userId);
    }

    private long available(long userId) {
        return jdbc.queryForObject("""
                SELECT total_token_quota
                    - COALESCE((SELECT SUM(input_tokens + output_tokens) FROM daily_api_usage WHERE user_id = ?), 0)
                    - COALESCE((SELECT SUM(tokens) FROM api_credit_reservation WHERE user_id = ? AND expires_at > CURRENT_TIMESTAMP), 0)
                FROM api_user_quota WHERE user_id = ?
                """, Long.class, userId, userId, userId);
    }

    @Transactional
    public UUID reserve(long userId, long tokens) {
        lock(userId);
        jdbc.update("DELETE FROM api_credit_reservation WHERE user_id = ? AND expires_at <= CURRENT_TIMESTAMP", userId);
        boolean unlimited = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT unlimited FROM api_user_quota WHERE user_id = ?", Boolean.class, userId));
        if (!unlimited) {
            long remaining = available(userId);
            if (remaining < tokens) throw new InsufficientApiCreditException(tokens, remaining, "请求预占估算");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_credit_reservation(request_id, user_id, tokens) VALUES (?, ?, ?)", id, userId, tokens);
        return id;
    }

    @Transactional
    public void release(UUID id) {
        jdbc.update("DELETE FROM api_credit_reservation WHERE request_id = ?", id);
    }

    public void renew(UUID id) {
        jdbc.update("UPDATE api_credit_reservation SET expires_at = CURRENT_TIMESTAMP + INTERVAL '15 minutes' WHERE request_id = ?", id);
    }

    @Transactional
    public void adjust(long userId, UUID operationId, String operation, long amount, String note, String actor) {
        lock(userId);
        int inserted = jdbc.update("INSERT INTO api_credit_adjustment(operation_id, user_id, actor, operation, amount, note) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (operation_id) DO NOTHING",
                operationId, userId, actor, operation, operation.equals("add") || operation.equals("deduct") ? amount : 0, note);
        if (inserted == 0) return;
        switch (operation) {
            case "add", "deduct" -> {
                if (amount <= 0) throw new ApiGatewayException("invalid_request", "Amount must be positive", 400);
                if (operation.equals("deduct") && available(userId) < amount) {
                    throw new ApiGatewayException("invalid_request", "Deduction exceeds available balance", 400);
                }
                jdbc.update("UPDATE api_user_quota SET total_token_quota = total_token_quota + ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                        operation.equals("add") ? amount : -amount, userId);
            }
            case "unlimited", "limited" -> jdbc.update(
                    "UPDATE api_user_quota SET unlimited = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                    operation.equals("unlimited"), userId);
            default -> throw new ApiGatewayException("invalid_request", "Unknown credit operation", 400);
        }
    }

    public List<Map<String, Object>> history(long userId) {
        return jdbc.queryForList("SELECT id, actor, operation, amount, note, created_at FROM api_credit_adjustment WHERE user_id = ? ORDER BY id DESC LIMIT 100", userId);
    }
}
