package com.lmplatform.common.repository;

import com.lmplatform.common.model.PlatformAnnouncement;
import com.lmplatform.common.model.PlatformAnnouncementRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores platform announcements as historical publishable content rather than mutable settings. */
@Repository
public class AnnouncementRepository {

    private final JdbcTemplate jdbc;

    public AnnouncementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PlatformAnnouncement> all() {
        return jdbc.query("""
                SELECT id, version_label, title, body, published, created_at, updated_at, published_at
                FROM platform_announcement
                ORDER BY COALESCE(published_at, updated_at) DESC, created_at DESC
                """, this::mapAnnouncement);
    }

    public List<PlatformAnnouncement> publishedHistory() {
        return jdbc.query("""
                SELECT id, version_label, title, body, published, created_at, updated_at, published_at
                FROM platform_announcement
                WHERE published = TRUE
                ORDER BY published_at DESC NULLS LAST, updated_at DESC
                """, this::mapAnnouncement);
    }

    public Optional<PlatformAnnouncement> current() {
        return jdbc.query("""
                SELECT id, version_label, title, body, published, created_at, updated_at, published_at
                FROM platform_announcement
                WHERE published = TRUE
                ORDER BY published_at DESC NULLS LAST, updated_at DESC
                LIMIT 1
                """, this::mapAnnouncement).stream().findFirst();
    }

    public Optional<PlatformAnnouncement> findById(UUID id) {
        return find(id);
    }

    @Transactional
    public PlatformAnnouncement create(PlatformAnnouncementRequest request) {
        validate(request);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform_announcement (id, version_label, title, body, published, published_at)
                VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, id, normalize(request.version()), normalize(request.title()), normalize(request.body()),
                request.published(), request.published());
        return find(id).orElseThrow();
    }

    @Transactional
    public Optional<PlatformAnnouncement> updateDraft(UUID id, PlatformAnnouncementRequest request) {
        validate(request);
        int updated = jdbc.update("""
                UPDATE platform_announcement
                SET version_label = ?,
                    title = ?,
                    body = ?,
                    published = ?,
                    published_at = CASE
                        WHEN ? = TRUE AND published_at IS NULL THEN CURRENT_TIMESTAMP
                        WHEN ? = FALSE THEN NULL
                        ELSE published_at
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND published = FALSE
                """, normalize(request.version()), normalize(request.title()), normalize(request.body()), request.published(),
                request.published(), request.published(), id);
        return updated == 0 ? Optional.empty() : find(id);
    }

    private Optional<PlatformAnnouncement> find(UUID id) {
        return jdbc.query("""
                SELECT id, version_label, title, body, published, created_at, updated_at, published_at
                FROM platform_announcement
                WHERE id = ?
                """, this::mapAnnouncement, id).stream().findFirst();
    }

    private PlatformAnnouncement mapAnnouncement(ResultSet rs, int rowNum) throws SQLException {
        return new PlatformAnnouncement(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("version_label"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getBoolean("published"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("published_at"))
        );
    }

    private void validate(PlatformAnnouncementRequest request) {
        String title = normalize(request.title());
        String version = normalize(request.version());
        String body = normalize(request.body());
        if (version.isBlank()) {
            throw new IllegalArgumentException("Announcement version is required");
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("Announcement title is required");
        }
        if (version.length() > 64) {
            throw new IllegalArgumentException("Announcement version must be 64 characters or fewer");
        }
        if (body.isBlank()) {
            throw new IllegalArgumentException("Announcement body is required");
        }
        if (title.length() > 120) {
            throw new IllegalArgumentException("Announcement title must be 120 characters or fewer");
        }
        if (body.length() > 5000) {
            throw new IllegalArgumentException("Announcement body must be 5000 characters or fewer");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
