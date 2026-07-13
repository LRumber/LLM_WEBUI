package com.lmplatform.api.model;

public record ApiKeyCreateResponse(
        ApiKeySummary key,
        String secret
) {
}
