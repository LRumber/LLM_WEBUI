package com.lmplatform.api.model;

import jakarta.validation.constraints.Min;

public record ApiQuotaUpdateRequest(
        @Min(0) long totalQuota,
        boolean unlimited
) {
}
