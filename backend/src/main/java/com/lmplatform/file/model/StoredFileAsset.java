package com.lmplatform.file.model;

import java.util.UUID;

/** Private object-store coordinates used only to retry parsing an owned upload. */
public record StoredFileAsset(
        UUID id,
        String objectKey,
        String originalName,
        String mediaType
) {
}
