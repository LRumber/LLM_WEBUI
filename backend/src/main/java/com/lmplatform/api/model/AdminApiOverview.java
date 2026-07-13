package com.lmplatform.api.model;

public record AdminApiOverview(
        long enabledKeyCount,
        long enabledModelCount,
        long todayRequestCount,
        long todayTokenCount,
        long totalRequestCount,
        long totalTokenCount
) {
}
