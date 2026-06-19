package com.lmplatform.admin.model;

import java.time.LocalDate;

/** One calendar day's aggregate model usage across all users. */
public record DailyUsageSummary(
        LocalDate date,
        long conversationCount,
        long requestCount,
        long inputTokens,
        long outputTokens
) {
}
