package com.lmplatform.admin.model;

import java.time.OffsetDateTime;

/** Compact shadow-user projection for administration and usage review. */
public record AdminUserSummary(
        long id,
        String accountNumber,
        String userNumber,
        String displayName,
        short status,
        boolean administrator,
        boolean teamAdministrator,
        OffsetDateTime lastLoginAt,
        long conversationCount,
        long totalTokens,
        long apiTotalQuota,
        long apiAvailableTokens,
        long apiReservedTokens,
        boolean apiQuotaUnlimited,
        long apiUsedTokens,
        long apiRequestCount,
        int apiKeyCount
) {
}
