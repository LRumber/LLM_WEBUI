package com.lmplatform.common.repository;

import com.lmplatform.common.model.ModelDefaults;
import com.lmplatform.common.model.DeploymentSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores mutable platform settings that should not require environment changes. */
@Repository
public class SystemSettingRepository {

    private static final String DEFAULT_EMBEDDING_MODEL = "default_embedding_model";
    private static final String DEFAULT_RERANKER_MODEL = "default_reranker_model";
    private static final String DEFAULT_SPEECH_MODEL = "default_speech_model";
    private static final String DEPLOYMENT_DOMAIN = "deployment_domain";
    private static final String DEPLOYMENT_HTTP_PORT = "deployment_http_port";
    private static final String DEPLOYMENT_HTTPS_PORT = "deployment_https_port";

    private final JdbcTemplate jdbc;

    public SystemSettingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns model defaults, using blank strings when a migration has not seeded a value yet. */
    public ModelDefaults modelDefaults() {
        return new ModelDefaults(
                setting(DEFAULT_EMBEDDING_MODEL),
                setting(DEFAULT_RERANKER_MODEL),
                setting(DEFAULT_SPEECH_MODEL)
        );
    }

    /** Saves model defaults as one operation so search/indexing never observes a half update. */
    @Transactional
    public ModelDefaults saveModelDefaults(ModelDefaults defaults) {
        upsert(DEFAULT_EMBEDDING_MODEL, normalize(defaults.embeddingModel()),
                "Default embedding-capable model key used by knowledge indexing and search.");
        upsert(DEFAULT_RERANKER_MODEL, normalize(defaults.rerankerModel()),
                "Default rerank-capable model key reserved for knowledge result reranking.");
        upsert(DEFAULT_SPEECH_MODEL, normalize(defaults.speechModel()),
                "Default speech-to-text model key used by browser voice input.");
        return modelDefaults();
    }

    public DeploymentSettings deploymentSettings() {
        return new DeploymentSettings(
                defaultValue(setting(DEPLOYMENT_DOMAIN), "ai.sitp.ac.cn"),
                port(setting(DEPLOYMENT_HTTP_PORT), 80),
                port(setting(DEPLOYMENT_HTTPS_PORT), 443)
        );
    }

    @Transactional
    public DeploymentSettings saveDeploymentSettings(DeploymentSettings settings) {
        String domain = normalize(settings.domain()).toLowerCase();
        if (!domain.matches("^[a-z0-9.-]+$") || !domain.endsWith(".sitp.ac.cn")) {
            throw new IllegalArgumentException("Domain must be a sitp.ac.cn host name");
        }
        validatePort(settings.httpPort());
        validatePort(settings.httpsPort());
        if (settings.httpPort() == settings.httpsPort()) {
            throw new IllegalArgumentException("HTTP and HTTPS ports must be different");
        }
        upsert(DEPLOYMENT_DOMAIN, domain, "Public domain served by the reverse proxy.");
        upsert(DEPLOYMENT_HTTP_PORT, String.valueOf(settings.httpPort()),
                "Desired host HTTP port applied by deploy/apply-ports.sh.");
        upsert(DEPLOYMENT_HTTPS_PORT, String.valueOf(settings.httpsPort()),
                "Desired host HTTPS port applied by deploy/apply-ports.sh.");
        return deploymentSettings();
    }

    private String setting(String key) {
        return jdbc.query("""
                SELECT setting_value FROM system_setting WHERE setting_key = ?
                """, (rs, rowNum) -> rs.getString("setting_value"), key).stream()
                .findFirst()
                .orElse("");
    }

    private void upsert(String key, String value, String description) {
        jdbc.update("""
                INSERT INTO system_setting (setting_key, setting_value, description)
                VALUES (?, ?, ?)
                ON CONFLICT (setting_key) DO UPDATE
                    SET setting_value = EXCLUDED.setting_value,
                        description = EXCLUDED.description,
                        updated_at = CURRENT_TIMESTAMP
                """, key, value, description);
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int port(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 && parsed <= 65535 ? parsed : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private void validatePort(int value) {
        if (value <= 0 || value > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
    }
}
