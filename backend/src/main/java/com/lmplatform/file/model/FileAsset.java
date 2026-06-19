package com.lmplatform.file.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Safe file metadata returned to clients without object-store credentials or internal paths. */
public record FileAsset(
        UUID id,
        String originalName,
        String mediaType,
        long sizeBytes,
        String assetKind,
        String status,
        String errorMessage,
        OffsetDateTime createdAt
) {
}
