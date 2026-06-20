package com.lmplatform.file.model;

/** Private object bytes and safe response metadata returned after ownership validation. */
public record DownloadedFile(
        String filename,
        String mediaType,
        byte[] content
) {
}
