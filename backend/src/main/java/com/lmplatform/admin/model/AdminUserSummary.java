package com.lmplatform.admin.model;

import java.time.OffsetDateTime;

/** Compact shadow-user projection for administration and usage review. */
public record AdminUserSummary(
        long id,
        String accountNumber,
        String displayName,
        short status,
        boolean administrator,
        OffsetDateTime lastLoginAt,
        long conversationCount,
        long totalTokens
) {
}
