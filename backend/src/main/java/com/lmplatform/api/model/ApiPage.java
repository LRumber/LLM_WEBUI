package com.lmplatform.api.model;

import java.util.List;

public record ApiPage<T>(List<T> items, long total, int page, int size, long pendingCount) {}
