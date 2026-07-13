package com.lmplatform.api.model;

import java.time.LocalDate;

public record ApiUsageDay(
        LocalDate date,
        long requestCount,
        long inputTokens,
        long outputTokens
) {
}
