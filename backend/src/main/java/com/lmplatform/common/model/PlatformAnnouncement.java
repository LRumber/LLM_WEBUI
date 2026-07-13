package com.lmplatform.common.model;

import java.time.Instant;

/** Historical platform announcement record managed by administrators. */
public record PlatformAnnouncement(
        String id,
        String version,
        String title,
        String body,
        boolean published,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt
) {
}
