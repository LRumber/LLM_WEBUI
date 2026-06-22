package com.lmplatform.team.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Team metadata visible to one direct member. */
public record TeamSummary(
        UUID id,
        String name,
        String description,
        String memberRole,
        int memberCount,
        OffsetDateTime createdAt
) {
}
