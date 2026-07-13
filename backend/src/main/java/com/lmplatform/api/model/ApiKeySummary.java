package com.lmplatform.api.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ApiKeySummary(
        UUID id,
        String name,
        String prefix,
        String maskedKey,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt
) {
}
