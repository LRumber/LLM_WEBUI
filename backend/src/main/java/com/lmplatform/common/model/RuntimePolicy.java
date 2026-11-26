package com.lmplatform.common.model;

import jakarta.validation.constraints.*;

public record RuntimePolicy(
        @Min(0) @Max(9007199254740991L) long defaultApiTokens,
        @Min(0) @Max(1000000) int dailyChatRequests,
        @Min(0) @Max(100) int concurrentRequests,
        boolean maintenance,
        @NotBlank @Size(max = 500) String maintenanceMessage
) {
    public static RuntimePolicy defaults() {
        return new RuntimePolicy(10000000, 0, 0, false, "系统维护中，请稍后再试。");
    }
}
