package com.lmplatform.admin.repository;

import com.lmplatform.admin.model.AdminOverview;
import com.lmplatform.admin.model.AdminUserPage;
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

    private static final long DEFAULT_API_TOKEN_QUOTA = 10_000_000L;

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

    /** Lists shadow users with search, pagination, conversation usage, and API quota state. */
    public AdminUserPage users(String query, int page, int size) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.max(10, Math.min(size, 100));
        int offset = normalizedPage * normalizedSize;
        String pattern = "%" + normalizedQuery + "%";
        Object[] searchParameters = new Object[] {
                normalizedQuery, pattern, pattern, pattern, pattern
        };
        long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM app_user user_account
                WHERE (? = ''
                   OR LOWER(COALESCE(user_account.account_number, '')) LIKE ?
                   OR LOWER(COALESCE(user_account.display_name, '')) LIKE ?
                   OR LOWER(COALESCE(user_account.user_number, '')) LIKE ?
                   OR LOWER(COALESCE(user_account.email, '')) LIKE ?)
                """, Long.class, searchParameters);
        List<AdminUserSummary> users = jdbc.query("""
                SELECT user_account.id, user_account.account_number, user_account.user_number,
                       user_account.display_name,
                       user_account.status, user_account.is_admin, user_account.is_team_admin,
                       user_account.last_login_at,
                       COALESCE(conversations.conversation_count, 0) AS conversation_count,
                       COALESCE(usage.total_tokens, 0) AS total_tokens,
                       COALESCE(api_quota.total_token_quota, ?) AS api_total_quota,
                       COALESCE(api_quota.unlimited, FALSE) AS api_quota_unlimited,
                       COALESCE((SELECT SUM(tokens) FROM api_credit_reservation WHERE user_id = user_account.id AND expires_at > CURRENT_TIMESTAMP), 0) AS reserved_tokens,
                       COALESCE(api_usage.used_tokens, 0) AS api_used_tokens,
                       COALESCE(api_usage.request_count, 0) AS api_request_count,
                       COALESCE(api_keys.key_count, 0) AS api_key_count
                FROM app_user user_account
                LEFT JOIN (
                    SELECT user_id, COUNT(*) AS conversation_count
                    FROM conversation WHERE deleted_at IS NULL GROUP BY user_id
                ) conversations ON conversations.user_id = user_account.id
                LEFT JOIN (
                    SELECT user_id, SUM(input_tokens + output_tokens) AS total_tokens
                    FROM daily_user_usage GROUP BY user_id
                ) usage ON usage.user_id = user_account.id
                LEFT JOIN api_user_quota api_quota ON api_quota.user_id = user_account.id
                LEFT JOIN (
                    SELECT user_id, SUM(input_tokens + output_tokens) AS used_tokens,
                           SUM(request_count) AS request_count
                    FROM daily_api_usage GROUP BY user_id
                ) api_usage ON api_usage.user_id = user_account.id
                LEFT JOIN (
                    SELECT user_id, COUNT(*) AS key_count FROM api_key GROUP BY user_id
                ) api_keys ON api_keys.user_id = user_account.id
                WHERE (? = ''
                   OR LOWER(COALESCE(user_account.account_number, '')) LIKE ?
                   OR LOWER(COALESCE(user_account.display_name, '')) LIKE ?
                   OR LOWER(COALESCE(user_account.user_number, '')) LIKE ?
                   OR LOWER(COALESCE(user_account.email, '')) LIKE ?)
                ORDER BY user_account.updated_at DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new AdminUserSummary(
                rs.getLong("id"),
                rs.getString("account_number"),
                rs.getString("user_number"),
                rs.getString("display_name"),
                rs.getShort("status"),
                rs.getBoolean("is_admin"),
                rs.getBoolean("is_team_admin"),
                rs.getObject("last_login_at", OffsetDateTime.class),
                rs.getLong("conversation_count"),
                rs.getLong("total_tokens"),
                rs.getLong("api_total_quota"),
                Math.max(0, rs.getLong("api_total_quota") - rs.getLong("api_used_tokens") - rs.getLong("reserved_tokens")),
                rs.getLong("reserved_tokens"),
                rs.getBoolean("api_quota_unlimited"),
                rs.getLong("api_used_tokens"),
                rs.getLong("api_request_count"),
                rs.getInt("api_key_count")
        ), DEFAULT_API_TOKEN_QUOTA, normalizedQuery, pattern, pattern, pattern, pattern, normalizedSize, offset);
        return new AdminUserPage(users, total, normalizedPage, normalizedSize);
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
