package com.lmplatform.api.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminApiKeySummary(
        UUID id,
        long userId,
        String accountNumber,
        String displayName,
        String name,
        String maskedKey,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt
) {
}
