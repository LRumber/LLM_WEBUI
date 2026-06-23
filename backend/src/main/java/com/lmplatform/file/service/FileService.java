package com.lmplatform.file.service;

import com.lmplatform.file.config.ObjectStorageProperties;
import com.lmplatform.file.model.FileAsset;
import com.lmplatform.file.model.DownloadedFile;
import com.lmplatform.file.repository.FileRepository;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Validates uploads, writes immutable objects, and schedules asynchronous extraction. */
@Service
public class FileService {

    private final ObjectStorageProperties properties;
    private final ObjectStorageService storage;
    private final FileRepository repository;
    private final FileProcessingService processingService;

    public FileService(
            ObjectStorageProperties properties,
            ObjectStorageService storage,
            FileRepository repository,
            FileProcessingService processingService
    ) {
        this.properties = properties;
        this.storage = storage;
        this.repository = repository;
        this.processingService = processingService;
    }

    public FileAsset upload(long userId, MultipartFile upload) throws Exception {
        if (upload.isEmpty() || upload.getSize() > properties.maxFileBytes()) {
            throw new IllegalArgumentException("File is empty or exceeds configured size limit");
        }
        String filename = sanitizeFilename(upload.getOriginalFilename());
        String mediaType = upload.getContentType() == null ? "application/octet-stream" : upload.getContentType();
        byte[] content = upload.getBytes();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String objectKey = userId + "/" + UUID.randomUUID() + "/" + filename;
        storage.put(objectKey, content, mediaType);
        UUID fileId = repository.create(
                userId, objectKey, filename, mediaType, content.length, sha256, kind(mediaType)
        );
        processingService.process(fileId, content, filename, mediaType);
        return repository.list(userId).stream().filter(item -> item.id().equals(fileId)).findFirst()
                .orElseThrow();
    }

    public List<FileAsset> list(long userId) {
        return repository.list(userId);
    }

    /** Reads an owned private object for an authenticated browser download. */
    public DownloadedFile download(long userId, UUID fileId) throws Exception {
        var stored = repository.findStored(fileId, userId)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
        int limit = Math.toIntExact(Math.min(properties.maxFileBytes(), Integer.MAX_VALUE));
        return new DownloadedFile(
                stored.originalName(), stored.mediaType(), storage.get(stored.objectKey(), limit)
        );
    }

    /** Reloads an existing private object and schedules parsing without requiring another upload. */
    public FileAsset retry(long userId, UUID fileId) throws Exception {
        var stored = repository.findStored(fileId, userId)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
        int limit = Math.toIntExact(Math.min(properties.maxFileBytes(), Integer.MAX_VALUE));
        byte[] content = storage.get(stored.objectKey(), limit);
        repository.requeue(fileId);
        processingService.process(fileId, content, stored.originalName(), stored.mediaType());
        return repository.list(userId).stream().filter(item -> item.id().equals(fileId)).findFirst()
                .orElseThrow();
    }

    private String sanitizeFilename(String value) {
        String name = value == null ? "upload.bin" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        return name.isBlank() ? "upload.bin" : name.substring(0, Math.min(name.length(), 255));
    }

    private String kind(String mediaType) {
        if (mediaType.startsWith("image/")) return "image";
        if (mediaType.startsWith("audio/")) return "audio";
        if (mediaType.startsWith("text/") || mediaType.contains("pdf") || mediaType.contains("document")
                || mediaType.contains("sheet") || mediaType.contains("presentation")
                || mediaType.contains("msword") || mediaType.contains("excel")
                || mediaType.contains("powerpoint")) return "document";
        return "other";
    }
}
