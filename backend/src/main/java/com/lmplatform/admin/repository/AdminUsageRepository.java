package com.lmplatform.admin.repository;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Uses immutable usage events so deleted conversations and model registrations retain their usage. */
@Repository
public class AdminUsageRepository {
    private static final String EVENTS = """
            WITH events AS (
                SELECT 'chat' AS source, model_key, input_tokens, output_tokens, status, occurred_at FROM usage_event
                UNION ALL
                SELECT 'api' AS source, model_key, input_tokens, output_tokens, status, occurred_at FROM api_usage_event
            )
            """;
    private final JdbcTemplate jdbc;

    public AdminUsageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Totals> totals() { return aggregate(null, null); }

    private List<Totals> aggregate(OffsetDateTime start, OffsetDateTime end) {
        String filter = start == null ? "" : "WHERE occurred_at >= ? AND occurred_at < ?";
        Object[] parameters = start == null ? new Object[] {} : new Object[] {start, end};
        return jdbc.query(EVENTS + """
                SELECT source, COUNT(*) AS requests, COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COUNT(*) FILTER (WHERE status <> 'completed') AS failed
                FROM events
                """ + filter + " GROUP BY source ORDER BY source", (rs, row) -> new Totals(
                rs.getString("source"), rs.getLong("requests"), rs.getLong("input_tokens"),
                rs.getLong("output_tokens"), rs.getLong("failed")), parameters);
    }

    public Report report(YearMonth month, boolean allModels) {
        var zone = ZoneId.of("Asia/Shanghai");
        OffsetDateTime start = month.atDay(1).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toOffsetDateTime();
        var months = jdbc.query(EVENTS + """
                SELECT to_char(occurred_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM') AS month,
                       source, COUNT(*) AS requests, SUM(input_tokens) AS input_tokens,
                       SUM(output_tokens) AS output_tokens
                FROM events WHERE occurred_at >= ? AND occurred_at < ?
                GROUP BY month, source ORDER BY month, source
                """, (rs, row) -> new Monthly(rs.getString("month"), rs.getString("source"),
                rs.getLong("requests"), rs.getLong("input_tokens"), rs.getLong("output_tokens")),
                month.minusMonths(11).atDay(1).atStartOfDay(zone).toOffsetDateTime(), end);
        String filter = allModels ? "" : "WHERE occurred_at >= ? AND occurred_at < ?";
        Object[] parameters = allModels ? new Object[] {} : new Object[] {start, end};
        var models = jdbc.query(EVENTS + """
                SELECT source, model_key, COUNT(*) AS requests, SUM(input_tokens) AS input_tokens,
                       SUM(output_tokens) AS output_tokens,
                       COUNT(*) FILTER (WHERE status <> 'completed') AS failed
                FROM events
                """ + filter + " GROUP BY source, model_key ORDER BY SUM(input_tokens + output_tokens) DESC, source, model_key",
                (rs, row) -> new ModelUsage(rs.getString("source"), rs.getString("model_key"), rs.getLong("requests"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getLong("failed")), parameters);
        return new Report(month.toString(), totals(), aggregate(start, end), months, models);
    }

    public record Totals(String source, long requestCount, long inputTokens, long outputTokens, long failedRequestCount) {}
    public record Monthly(String month, String source, long requestCount, long inputTokens, long outputTokens) {}
    public record ModelUsage(String source, String modelKey, long requestCount, long inputTokens, long outputTokens, long failedRequestCount) {}
    public record Report(String month, List<Totals> totals, List<Totals> selectedMonth, List<Monthly> months, List<ModelUsage> models) {}
}
