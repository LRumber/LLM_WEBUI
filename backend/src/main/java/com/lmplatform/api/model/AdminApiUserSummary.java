package com.lmplatform.api.model;

public record AdminApiUserSummary(
        long userId,
        String accountNumber,
        String displayName,
        long totalQuota,
        long usedTokens,
        long requestCount,
        int keyCount
) {
}
