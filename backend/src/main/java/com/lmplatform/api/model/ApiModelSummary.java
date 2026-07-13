package com.lmplatform.api.model;

import java.util.List;

public record ApiModelSummary(
        String key,
        String displayName,
        String category,
        List<String> capabilities,
        Integer contextWindow,
        boolean apiEnabled,
        boolean available
) {
}
