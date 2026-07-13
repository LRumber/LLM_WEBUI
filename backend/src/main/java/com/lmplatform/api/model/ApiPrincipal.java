package com.lmplatform.api.model;

import java.util.UUID;

public record ApiPrincipal(
        UUID keyId,
        long userId,
        String keyPrefix,
        long totalQuota,
        long usedTokens
) {
    public long remainingTokens() {
        return Math.max(0, totalQuota - usedTokens);
    }
}
