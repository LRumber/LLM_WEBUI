package com.lmplatform.admin.repository;

import com.lmplatform.admin.model.LocalAdminCredential;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists local-only administration accounts independently of OAuth2 users. */
@Repository
public class AdminAccountRepository {

    private final JdbcTemplate jdbc;

    public AdminAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LocalAdminCredential> findByUsername(String username) {
        return jdbc.query("""
                SELECT id, username, password_hash, display_name, status
                FROM admin_account WHERE username = ?
                """, (rs, rowNum) -> new LocalAdminCredential(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getShort("status")
        ), username).stream().findFirst();
    }

    public void recordSuccessfulLogin(long accountId) {
        jdbc.update("""
                UPDATE admin_account
                SET last_login_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, accountId);
    }

    public void createIfAbsent(String username, String passwordHash, String displayName) {
        jdbc.update("""
                INSERT INTO admin_account (username, password_hash, display_name)
                VALUES (?, ?, ?)
                ON CONFLICT (username) DO NOTHING
                """, username, passwordHash, displayName);
    }
}
