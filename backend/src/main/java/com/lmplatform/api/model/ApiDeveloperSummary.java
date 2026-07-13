package com.lmplatform.api.model;

import java.util.List;

public record ApiDeveloperSummary(
        String baseUrl,
        ApiQuotaSummary quota,
        List<ApiModelSummary> models,
        List<ApiUsageDay> dailyUsage
) {
}
