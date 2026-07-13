package com.lmplatform.api.model;

public record ApiQuotaSummary(
        long totalQuota,
        long usedTokens,
        long remainingTokens,
        long requestCount
) {
}
