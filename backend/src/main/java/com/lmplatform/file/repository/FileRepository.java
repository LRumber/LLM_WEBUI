package com.lmplatform.file.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.lmplatform.file.model.FileAsset;
import com.lmplatform.file.model.StoredFileAsset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persists file metadata and durable parsing-task state; binary content remains in MinIO. */
@Repository
public class FileRepository {

    private final JdbcTemplate jdbc;

    public FileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID create(
            long userId, String objectKey, String name, String mediaType,
            long size, String sha256, String kind
    ) {
        UUID fileId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO file_asset (
                    id, owner_user_id, object_key, original_name, media_type,
                    size_bytes, sha256, asset_kind
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, fileId, userId, objectKey, name, mediaType, size, sha256, kind);
        jdbc.update("""
                INSERT INTO processing_task (id, task_type, resource_id)
                VALUES (?, 'document_parse', ?)
                """, UUID.randomUUID(), fileId);
        return fileId;
    }

    public void markProcessing(UUID fileId) {
        jdbc.update("UPDATE file_asset SET status = 'processing', updated_at = CURRENT_TIMESTAMP WHERE id = ?", fileId);
        jdbc.update("""
                UPDATE processing_task SET status = 'running', attempts = attempts + 1,
                    started_at = CURRENT_TIMESTAMP WHERE resource_id = ? AND status = 'queued'
                """, fileId);
    }

    @Transactional
    public void complete(UUID fileId, JsonNode result) {
        jdbc.update("""
                UPDATE file_asset SET status = 'ready', extracted_text = ?, parse_metadata = ?::jsonb,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, result.path("text").asText(), result.toString(), fileId);
        jdbc.update("""
                UPDATE processing_task SET status = 'completed', finished_at = CURRENT_TIMESTAMP
                WHERE resource_id = ? AND status = 'running'
                """, fileId);
    }

    @Transactional
    public void fail(UUID fileId, String message) {
        jdbc.update("""
                UPDATE file_asset SET status = 'failed', error_message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, message, fileId);
        jdbc.update("""
                UPDATE processing_task SET status = 'failed', error_message = ?, finished_at = CURRENT_TIMESTAMP
                WHERE resource_id = ? AND status = 'running'
                """, message, fileId);
    }

    public List<FileAsset> list(long userId) {
        return jdbc.query("""
                SELECT id, original_name, media_type, size_bytes, asset_kind, status,
                       error_message, created_at FROM file_asset
                WHERE owner_user_id = ? ORDER BY created_at DESC LIMIT 100
                """, (rs, rowNum) -> new FileAsset(
                rs.getObject("id", UUID.class), rs.getString("original_name"),
                rs.getString("media_type"), rs.getLong("size_bytes"),
                rs.getString("asset_kind"), rs.getString("status"),
                rs.getString("error_message"), rs.getObject("created_at", OffsetDateTime.class)
        ), userId);
    }

    /** Resolves an owned private object without exposing its object key through the HTTP API. */
    public Optional<StoredFileAsset> findStored(UUID fileId, long userId) {
        return jdbc.query("""
                SELECT id, object_key, original_name, media_type FROM file_asset
                WHERE id = ? AND owner_user_id = ?
                """, (rs, rowNum) -> new StoredFileAsset(
                rs.getObject("id", UUID.class), rs.getString("object_key"),
                rs.getString("original_name"), rs.getString("media_type")
        ), fileId, userId).stream().findFirst();
    }

    /** Returns a failed or interrupted task to the queue before an explicit retry. */
    @Transactional
    public void requeue(UUID fileId) {
        jdbc.update("""
                UPDATE file_asset SET status = 'uploaded', error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, fileId);
        jdbc.update("""
                UPDATE processing_task SET status = 'queued', error_message = NULL,
                    available_at = CURRENT_TIMESTAMP, started_at = NULL, finished_at = NULL
                WHERE resource_id = ? AND status <> 'completed'
                """, fileId);
    }
}
