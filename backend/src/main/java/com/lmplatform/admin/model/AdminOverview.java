package com.lmplatform.admin.model;

/** Headline platform totals displayed on the administration dashboard. */
public record AdminOverview(
        long userCount,
        long conversationCount,
        long todayRequestCount,
        long todayTokenCount
) {
}
