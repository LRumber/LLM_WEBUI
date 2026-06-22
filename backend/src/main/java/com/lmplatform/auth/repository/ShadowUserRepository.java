package com.lmplatform.auth.repository;

import com.lmplatform.auth.model.AuthenticatedShadowUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Synchronizes identity-center-owned profile fields while preserving local authorization flags. */
@Repository
public class ShadowUserRepository {

    private final JdbcTemplate jdbc;

    public ShadowUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AuthenticatedShadowUser upsert(
            long externalUserId,
            String accountNumber,
            String displayName,
            String userNumber,
            Long tenantId,
            String email,
            String telephone,
            String avatarPath,
            short status
    ) {
        return jdbc.queryForObject("""
                INSERT INTO app_user (
                    external_user_id, account_number, display_name, user_number, tenant_id,
                    email, telephone, avatar_path, status, last_login_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (external_user_id) DO UPDATE SET
                    account_number = EXCLUDED.account_number,
                    display_name = EXCLUDED.display_name,
                    user_number = EXCLUDED.user_number,
                    tenant_id = EXCLUDED.tenant_id,
                    email = EXCLUDED.email,
                    telephone = EXCLUDED.telephone,
                    avatar_path = EXCLUDED.avatar_path,
                    status = EXCLUDED.status,
                    last_login_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id, account_number, display_name, is_admin, is_team_admin
                """, (rs, rowNum) -> new AuthenticatedShadowUser(
                rs.getLong("id"), rs.getString("account_number"), rs.getString("display_name"),
                rs.getBoolean("is_admin"), rs.getBoolean("is_team_admin")
        ), externalUserId, accountNumber, displayName, userNumber, tenantId,
                email, telephone, avatarPath, status);
    }
}
