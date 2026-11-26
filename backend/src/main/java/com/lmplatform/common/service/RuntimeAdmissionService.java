package com.lmplatform.common.service;

import com.lmplatform.common.model.RuntimePolicy;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class RuntimeAdmissionService {
    private final JdbcTemplate jdbc;
    public RuntimeAdmissionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional
    public UUID acquire(long userId, boolean chat, RuntimePolicy policy) {
        var active = jdbc.query("SELECT id FROM app_user WHERE id = ? AND status = 1 FOR UPDATE", (rs, row) -> rs.getLong(1), userId);
        if (active.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号不可用");
        jdbc.update("DELETE FROM runtime_request_lease WHERE user_id = ? AND expires_at <= CURRENT_TIMESTAMP", userId);
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM runtime_request_lease WHERE user_id = ?", Long.class, userId);
        if (policy.concurrentRequests() > 0 && count >= policy.concurrentRequests()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "已达到每用户并发上限，请等待当前请求完成");
        }
        if (chat) {
            jdbc.update("""
                    INSERT INTO runtime_daily_requests(user_id, usage_date) VALUES (?, (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date)
                    ON CONFLICT DO NOTHING
                    """, userId);
            long daily = jdbc.queryForObject("SELECT requests FROM runtime_daily_requests WHERE user_id = ? AND usage_date = (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date", Long.class, userId);
            if (policy.dailyChatRequests() > 0 && daily >= policy.dailyChatRequests()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "已达到今日网页对话请求上限，请明日再试或联系管理员");
            }
            jdbc.update("UPDATE runtime_daily_requests SET requests = requests + 1 WHERE user_id = ? AND usage_date = (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date", userId);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO runtime_request_lease(id, user_id) VALUES (?, ?)", id, userId);
        return id;
    }
    public void release(UUID id) { jdbc.update("DELETE FROM runtime_request_lease WHERE id = ?", id); }
    public void renew(UUID id) { jdbc.update("UPDATE runtime_request_lease SET expires_at = CURRENT_TIMESTAMP + INTERVAL '15 minutes' WHERE id = ?", id); }
}
