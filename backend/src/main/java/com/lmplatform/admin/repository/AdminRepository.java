package com.lmplatform.admin.repository;

import com.lmplatform.admin.model.AdminOverview;
import com.lmplatform.admin.model.AdminUserSummary;
import com.lmplatform.admin.model.DailyUsageSummary;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only aggregate queries backing the basic administration dashboard. */
@Repository
public class AdminRepository {

    private final JdbcTemplate jdbc;

    public AdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns platform totals while excluding soft-deleted conversations. */
    public AdminOverview overview() {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM app_user) AS user_count,
                    (SELECT COUNT(*) FROM conversation WHERE deleted_at IS NULL) AS conversation_count,
                    COALESCE((SELECT SUM(request_count) FROM daily_user_usage
                              WHERE usage_date = CURRENT_DATE), 0) AS today_request_count,
                    COALESCE((SELECT SUM(input_tokens + output_tokens) FROM daily_user_usage
                              WHERE usage_date = CURRENT_DATE), 0) AS today_token_count
                """, (rs, rowNum) -> new AdminOverview(
                rs.getLong("user_count"),
                rs.getLong("conversation_count"),
                rs.getLong("today_request_count"),
                rs.getLong("today_token_count")
        ));
    }

    /** Aggregates recent usage across users, returning zero-filled calendar days. */
    public List<DailyUsageSummary> dailyUsage(int days) {
        return jdbc.query("""
                SELECT calendar.usage_date::date AS usage_date,
                       COALESCE(SUM(usage.conversation_count), 0) AS conversation_count,
                       COALESCE(SUM(usage.request_count), 0) AS request_count,
                       COALESCE(SUM(usage.input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(usage.output_tokens), 0) AS output_tokens
                FROM generate_series(
                    CURRENT_DATE - (? - 1) * INTERVAL '1 day',
                    CURRENT_DATE,
                    INTERVAL '1 day'
                ) AS calendar(usage_date)
                LEFT JOIN daily_user_usage usage ON usage.usage_date = calendar.usage_date::date
                GROUP BY calendar.usage_date
                ORDER BY calendar.usage_date
                """, (rs, rowNum) -> new DailyUsageSummary(
                rs.getObject("usage_date", LocalDate.class),
                rs.getLong("conversation_count"),
                rs.getLong("request_count"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens")
        ), days);
    }

    /** Lists recently active users with all-time conversation and token totals. */
    public List<AdminUserSummary> users() {
        return jdbc.query("""
                SELECT user_account.id, user_account.account_number, user_account.display_name,
                       user_account.status, user_account.is_admin, user_account.is_team_admin,
                       user_account.last_login_at,
                       COALESCE(conversations.conversation_count, 0) AS conversation_count,
                       COALESCE(usage.total_tokens, 0) AS total_tokens
                FROM app_user user_account
                LEFT JOIN (
                    SELECT user_id, COUNT(*) AS conversation_count
                    FROM conversation WHERE deleted_at IS NULL GROUP BY user_id
                ) conversations ON conversations.user_id = user_account.id
                LEFT JOIN (
                    SELECT user_id, SUM(input_tokens + output_tokens) AS total_tokens
                    FROM daily_user_usage GROUP BY user_id
                ) usage ON usage.user_id = user_account.id
                ORDER BY user_account.updated_at DESC
                LIMIT 100
                """, (rs, rowNum) -> new AdminUserSummary(
                rs.getLong("id"),
                rs.getString("account_number"),
                rs.getString("display_name"),
                rs.getShort("status"),
                rs.getBoolean("is_admin"),
                rs.getBoolean("is_team_admin"),
                rs.getObject("last_login_at", OffsetDateTime.class),
                rs.getLong("conversation_count"),
                rs.getLong("total_tokens")
        ));
    }

    /** Grants or revokes local administration rights without modifying OAuth2-owned identity data. */
    public boolean setUserAdministrator(long userId, boolean enabled) {
        int updated = jdbc.update("""
                UPDATE app_user SET is_admin = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, enabled, userId);
        return updated > 0;
    }

    /** Grants team creation rights without granting access to the system administration console. */
    public boolean setTeamAdministrator(long userId, boolean enabled) {
        return jdbc.update("""
                UPDATE app_user SET is_team_admin = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, enabled, userId) == 1;
    }
}
