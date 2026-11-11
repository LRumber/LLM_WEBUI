package com.lmplatform.api.repository;

import com.lmplatform.api.model.ApiPage;
import com.lmplatform.api.service.ApiCallTrace;
import com.lmplatform.api.service.ApiGatewayException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApiCallLogRepository {
    private final JdbcTemplate jdbc;
    public ApiCallLogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(ApiCallTrace trace, String endpoint, int status) {
        var failure = trace.failure;
        jdbc.update("""
                INSERT INTO api_call_log(request_id,user_id,api_key_id,model,endpoint,stream,http_status,upstream_status,
                    status,error_code,error_message,detail,input_tokens,output_tokens,estimated,duration_ms,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (request_id) DO NOTHING
                """, trace.requestId, trace.userId, trace.keyId, trace.model,
                endpoint.substring(0, Math.min(128, endpoint.length())), trace.stream, status,
                failure == null ? null : failure.upstreamStatus(), failure == null && status < 400 ? "success" : "failed",
                failure == null ? null : failure.code(), failure == null ? null : failure.message(),
                failure == null ? null : failure.detail(), trace.inputTokens, trace.outputTokens, trace.estimated,
                (System.nanoTime() - trace.started) / 1_000_000L, trace.createdAt);
    }

    public ApiPage<LogRow> list(Long userId, String status, String q, String model, String from, String to, int page, int size) {
        if (!java.util.Set.of("all", "success", "failed").contains(status)) throw new ApiGatewayException("invalid_request", "调用状态无效", 400);
        int p = Math.max(0, Math.min(page, 1_000_000));
        int s = Math.max(1, Math.min(size, 100));
        var parameters = new ArrayList<Object>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (userId != null) { where.append(" AND l.user_id=?"); parameters.add(userId); }
        if (!status.equals("all")) { where.append(" AND l.status=?"); parameters.add(status); }
        if (model != null && !model.isBlank()) { where.append(" AND l.model=?"); parameters.add(model); }
        if (q != null && !q.isBlank()) {
            where.append(" AND (u.display_name ILIKE ? OR u.user_number ILIKE ? OR l.request_id::text ILIKE ?)");
            String term = "%" + q.strip().substring(0, Math.min(128, q.strip().length())) + "%";
            parameters.add(term); parameters.add(term); parameters.add(term);
        }
        try {
            LocalDate lower = from == null || from.isBlank() ? null : LocalDate.parse(from);
            LocalDate upper = to == null || to.isBlank() ? null : LocalDate.parse(to);
            if (lower != null && upper != null && lower.isAfter(upper)) throw new ApiGatewayException("invalid_request", "开始日期不能晚于结束日期", 400);
            if (lower != null) { where.append(" AND l.created_at>=?"); parameters.add(lower.atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime()); }
            if (upper != null) { where.append(" AND l.created_at<?"); parameters.add(upper.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime()); }
        } catch (DateTimeException | ArithmeticException exception) {
            throw new ApiGatewayException("invalid_request", "日期格式无效", 400);
        }
        String tables = " FROM api_call_log l LEFT JOIN app_user u ON u.id=l.user_id LEFT JOIN api_key k ON k.id=l.api_key_id";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + tables + where, Long.class, parameters.toArray());
        parameters.add(s); parameters.add((long) p * s);
        var rows = jdbc.query("SELECT l.*,u.display_name,u.user_number,k.name AS key_name,k.key_prefix" + tables + where
                + " ORDER BY l.created_at DESC,l.request_id DESC LIMIT ? OFFSET ?", (rs, row) -> new LogRow(
                rs.getObject("request_id", UUID.class), rs.getString("display_name"), rs.getString("user_number"),
                rs.getString("key_name"), rs.getString("key_prefix") == null ? null : rs.getString("key_prefix") + "****",
                rs.getString("model"), rs.getString("endpoint"), rs.getBoolean("stream"), rs.getInt("http_status"),
                rs.getObject("upstream_status", Integer.class), rs.getString("status"), rs.getString("error_code"),
                rs.getString("error_message"), userId == null ? rs.getString("detail") : null,
                rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getBoolean("estimated"),
                rs.getLong("duration_ms"), rs.getObject("created_at", OffsetDateTime.class)), parameters.toArray());
        return new ApiPage<>(rows, total, p, s, 0);
    }

    public record LogRow(UUID requestId, String displayName, String userNumber, String keyName, String maskedKey,
            String model, String endpoint, boolean stream, int httpStatus, Integer upstreamStatus, String status,
            String errorCode, String errorMessage, String detail, long inputTokens, long outputTokens,
            boolean estimated, long durationMs, OffsetDateTime createdAt) {}
}
