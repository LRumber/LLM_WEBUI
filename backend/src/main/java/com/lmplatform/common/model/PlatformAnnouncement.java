package com.lmplatform.common.model;

/** Editable platform announcement shown once per published revision in the user workspace. */
public record PlatformAnnouncement(
        String id,
        String title,
        String body,
        boolean published
) {
}
