package com.lmplatform.api.model;

public record AdminApiUserSummary(
        long userId,
        String accountNumber,
        String displayName,
        long totalQuota,
        boolean quotaUnlimited,
        long usedTokens,
        long requestCount,
        int keyCount
) {
}
