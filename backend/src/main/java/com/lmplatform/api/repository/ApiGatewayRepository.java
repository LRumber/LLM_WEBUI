package com.lmplatform.api.repository;

import com.lmplatform.admin.model.DailyUsageSummary;
import com.lmplatform.api.model.AdminApiOverview;
import com.lmplatform.api.model.AdminApiKeySummary;
import com.lmplatform.api.model.AdminApiUserSummary;
import com.lmplatform.api.model.ApiKeySummary;
import com.lmplatform.api.model.ApiPrincipal;
import com.lmplatform.api.model.ApiQuotaSummary;
import com.lmplatform.api.model.ApiUsageDay;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApiGatewayRepository {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long DEFAULT_API_TOKEN_QUOTA = 10_000_000L;

    private final JdbcTemplate jdbc;

    public ApiGatewayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CreatedApiKey createKey(long userId, String name) {
        String secret = newSecret();
        String prefix = secret.substring(0, Math.min(secret.length(), 18));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO api_key (id, user_id, name, key_prefix, key_hash)
                VALUES (?, ?, ?, ?, ?)
                """, id, userId, name.strip(), prefix, hash(secret));
        return new CreatedApiKey(findKey(id), secret);
    }

    public CreatedApiKey resetKey(long userId, UUID keyId) {
        String secret = newSecret();
        String prefix = secret.substring(0, Math.min(secret.length(), 18));
        int updated = jdbc.update("""
                UPDATE api_key
                SET key_prefix = ?, key_hash = ?, enabled = TRUE, revoked_at = NULL
                WHERE id = ? AND user_id = ?
                """, prefix, hash(secret), keyId, userId);
        if (updated != 1) {
            throw new IllegalArgumentException("API key not found");
        }
        return new CreatedApiKey(findKey(keyId), secret);
    }

    public boolean setKeyEnabled(UUID keyId, boolean enabled) {
        return jdbc.update("""
                UPDATE api_key SET enabled = ?, revoked_at = CASE WHEN ? THEN NULL ELSE CURRENT_TIMESTAMP END
                WHERE id = ?
                """, enabled, enabled, keyId) == 1;
    }

    public List<ApiKeySummary> keysForUser(long userId) {
        return jdbc.query("""
                SELECT id, name, key_prefix, enabled, created_at, last_used_at
                FROM api_key
                WHERE user_id = ?
                ORDER BY created_at DESC
                """, (rs, rowNum) -> mapKey(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getString("key_prefix"), rs.getBoolean("enabled"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("last_used_at", OffsetDateTime.class)), userId);
    }

    public List<AdminApiKeySummary> allKeys() {
        return jdbc.query("""
                SELECT api_key.id, api_key.user_id, app_user.account_number, app_user.display_name,
                       api_key.name, api_key.key_prefix, api_key.enabled, api_key.created_at, api_key.last_used_at
                FROM api_key
                JOIN app_user ON app_user.id = api_key.user_id
                ORDER BY api_key.created_at DESC
                LIMIT 500
                """, (rs, rowNum) -> new AdminApiKeySummary(
                rs.getObject("id", UUID.class),
                rs.getLong("user_id"),
                rs.getString("account_number"),
                rs.getString("display_name"),
                rs.getString("name"),
                rs.getString("key_prefix") + "****",
                rs.getBoolean("enabled"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("last_used_at", OffsetDateTime.class)));
    }

    public Optional<ApiPrincipal> authenticate(String secret) {
        return jdbc.query("""
                SELECT api_key.id, api_key.user_id, api_key.key_prefix,
                       COALESCE(api_user_quota.total_token_quota, ?) AS quota,
                       COALESCE(usage.used_tokens, 0) AS used_tokens
                FROM api_key
                JOIN app_user ON app_user.id = api_key.user_id
                LEFT JOIN api_user_quota ON api_user_quota.user_id = api_key.user_id
                LEFT JOIN (
                    SELECT user_id, SUM(input_tokens + output_tokens) AS used_tokens
                    FROM daily_api_usage GROUP BY user_id
                ) usage ON usage.user_id = api_key.user_id
                WHERE api_key.key_hash = ? AND api_key.enabled = TRUE
                  AND api_key.revoked_at IS NULL AND app_user.status = 1
                """, (rs, rowNum) -> new ApiPrincipal(
                rs.getObject("id", UUID.class),
                rs.getLong("user_id"),
                rs.getString("key_prefix"),
                rs.getLong("quota"),
                rs.getLong("used_tokens")
        ), DEFAULT_API_TOKEN_QUOTA, hash(secret)).stream().findFirst();
    }

    public ApiQuotaSummary quota(long userId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(api_user_quota.total_token_quota, ?) AS quota,
                       COALESCE(SUM(daily_api_usage.input_tokens + daily_api_usage.output_tokens), 0) AS used_tokens,
                       COALESCE(SUM(daily_api_usage.request_count), 0) AS request_count
                FROM app_user
                LEFT JOIN api_user_quota ON api_user_quota.user_id = app_user.id
                LEFT JOIN daily_api_usage ON daily_api_usage.user_id = app_user.id
                WHERE app_user.id = ?
                GROUP BY api_user_quota.total_token_quota
                """, (rs, rowNum) -> {
            long quota = rs.getLong("quota");
            long used = rs.getLong("used_tokens");
            return new ApiQuotaSummary(quota, used, Math.max(0, quota - used), rs.getLong("request_count"));
        }, DEFAULT_API_TOKEN_QUOTA, userId);
    }

    public void setQuota(long userId, long totalQuota) {
        jdbc.update("""
                INSERT INTO api_user_quota (user_id, total_token_quota, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id) DO UPDATE SET
                    total_token_quota = EXCLUDED.total_token_quota,
                    updated_at = CURRENT_TIMESTAMP
                """, userId, totalQuota);
    }

    public boolean isModelEnabled(String modelKey) {
        Boolean enabled = jdbc.query("""
                SELECT enabled FROM api_model_access WHERE model_key = ?
                """, (rs, rowNum) -> rs.getBoolean("enabled"), modelKey).stream().findFirst().orElse(false);
        return enabled;
    }

    public void setModelEnabled(String modelKey, boolean enabled) {
        jdbc.update("""
                INSERT INTO api_model_access (model_key, enabled, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (model_key) DO UPDATE SET
                    enabled = EXCLUDED.enabled,
                    updated_at = CURRENT_TIMESTAMP
                """, modelKey, enabled);
    }

    public List<String> enabledModelKeys() {
        return jdbc.query("""
                SELECT model_key FROM api_model_access WHERE enabled = TRUE ORDER BY model_key
                """, (rs, rowNum) -> rs.getString("model_key"));
    }

    public List<ApiUsageDay> dailyUsage(long userId, int days) {
        return jdbc.query("""
                SELECT calendar.usage_date::date AS usage_date,
                       COALESCE(usage.request_count, 0) AS request_count,
                       COALESCE(usage.input_tokens, 0) AS input_tokens,
                       COALESCE(usage.output_tokens, 0) AS output_tokens
                FROM generate_series(
                    CURRENT_DATE - (? - 1) * INTERVAL '1 day',
                    CURRENT_DATE,
                    INTERVAL '1 day'
                ) AS calendar(usage_date)
                LEFT JOIN daily_api_usage usage
                  ON usage.usage_date = calendar.usage_date::date AND usage.user_id = ?
                ORDER BY calendar.usage_date
                """, (rs, rowNum) -> new ApiUsageDay(
                rs.getObject("usage_date", LocalDate.class),
                rs.getLong("request_count"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens")
        ), days, userId);
    }

    public void recordUsage(
            UUID requestId,
            ApiPrincipal principal,
            String model,
            String endpoint,
            int inputTokens,
            int outputTokens,
            String status,
            String errorCode,
            int durationMs
    ) {
        jdbc.update("""
                INSERT INTO api_usage_event (
                    request_id, user_id, api_key_id, model_key, endpoint,
                    input_tokens, output_tokens, status, error_code, duration_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, requestId, principal.userId(), principal.keyId(), model, endpoint,
                inputTokens, outputTokens, status, errorCode, durationMs);
        jdbc.update("""
                INSERT INTO daily_api_usage (
                    usage_date, user_id, request_count, input_tokens, output_tokens
                ) VALUES (CURRENT_DATE, ?, 1, ?, ?)
                ON CONFLICT (usage_date, user_id) DO UPDATE SET
                    request_count = daily_api_usage.request_count + 1,
                    input_tokens = daily_api_usage.input_tokens + EXCLUDED.input_tokens,
                    output_tokens = daily_api_usage.output_tokens + EXCLUDED.output_tokens,
                    last_aggregated_at = CURRENT_TIMESTAMP
                """, principal.userId(), inputTokens, outputTokens);
        jdbc.update("UPDATE api_key SET last_used_at = CURRENT_TIMESTAMP WHERE id = ?", principal.keyId());
    }

    public AdminApiOverview adminOverview() {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM api_key WHERE enabled = TRUE AND revoked_at IS NULL) AS enabled_key_count,
                    (SELECT COUNT(*) FROM api_model_access WHERE enabled = TRUE) AS enabled_model_count,
                    COALESCE((SELECT SUM(request_count) FROM daily_api_usage WHERE usage_date = CURRENT_DATE), 0) AS today_requests,
                    COALESCE((SELECT SUM(input_tokens + output_tokens) FROM daily_api_usage WHERE usage_date = CURRENT_DATE), 0) AS today_tokens,
                    COALESCE((SELECT COUNT(*) FROM api_usage_event), 0) AS total_requests,
                    COALESCE((SELECT SUM(input_tokens + output_tokens) FROM api_usage_event), 0) AS total_tokens
                """, (rs, rowNum) -> new AdminApiOverview(
                rs.getLong("enabled_key_count"),
                rs.getLong("enabled_model_count"),
                rs.getLong("today_requests"),
                rs.getLong("today_tokens"),
                rs.getLong("total_requests"),
                rs.getLong("total_tokens")
        ));
    }

    public List<DailyUsageSummary> adminDailyUsage(int days) {
        return jdbc.query("""
                SELECT calendar.usage_date::date AS usage_date,
                       0 AS conversation_count,
                       COALESCE(SUM(usage.request_count), 0) AS request_count,
                       COALESCE(SUM(usage.input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(usage.output_tokens), 0) AS output_tokens
                FROM generate_series(
                    CURRENT_DATE - (? - 1) * INTERVAL '1 day',
                    CURRENT_DATE,
                    INTERVAL '1 day'
                ) AS calendar(usage_date)
                LEFT JOIN daily_api_usage usage ON usage.usage_date = calendar.usage_date::date
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

    public List<AdminApiUserSummary> adminUsers() {
        return jdbc.query("""
                SELECT app_user.id, app_user.account_number, app_user.display_name,
                       COALESCE(api_user_quota.total_token_quota, ?) AS quota,
                       COALESCE(usage.used_tokens, 0) AS used_tokens,
                       COALESCE(usage.request_count, 0) AS request_count,
                       COALESCE(keys.key_count, 0) AS key_count
                FROM app_user
                LEFT JOIN api_user_quota ON api_user_quota.user_id = app_user.id
                LEFT JOIN (
                    SELECT user_id, SUM(input_tokens + output_tokens) AS used_tokens,
                           SUM(request_count) AS request_count
                    FROM daily_api_usage GROUP BY user_id
                ) usage ON usage.user_id = app_user.id
                LEFT JOIN (
                    SELECT user_id, COUNT(*) AS key_count FROM api_key GROUP BY user_id
                ) keys ON keys.user_id = app_user.id
                ORDER BY app_user.updated_at DESC
                LIMIT 200
                """, (rs, rowNum) -> new AdminApiUserSummary(
                rs.getLong("id"),
                rs.getString("account_number"),
                rs.getString("display_name"),
                rs.getLong("quota"),
                rs.getLong("used_tokens"),
                rs.getLong("request_count"),
                rs.getInt("key_count")
        ), DEFAULT_API_TOKEN_QUOTA);
    }

    private ApiKeySummary findKey(UUID keyId) {
        return jdbc.queryForObject("""
                SELECT id, name, key_prefix, enabled, created_at, last_used_at
                FROM api_key WHERE id = ?
                """, (rs, rowNum) -> mapKey(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getString("key_prefix"), rs.getBoolean("enabled"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("last_used_at", OffsetDateTime.class)), keyId);
    }

    private ApiKeySummary mapKey(
            UUID id, String name, String prefix, boolean enabled,
            OffsetDateTime createdAt, OffsetDateTime lastUsedAt
    ) {
        return new ApiKeySummary(id, name, prefix, prefix + "****", enabled, createdAt, lastUsedAt);
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "sk-sitp-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record CreatedApiKey(ApiKeySummary key, String secret) {
    }
}
