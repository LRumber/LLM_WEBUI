package com.lmplatform.common.model;

/** Administrator payload for drafting or publishing the platform announcement. */
public record PlatformAnnouncementRequest(
        String title,
        String body,
        boolean published
) {
}
