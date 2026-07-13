package com.lmplatform.admin.model;

import java.util.List;

/** Paginated administration view over OAuth2 shadow users. */
public record AdminUserPage(
        List<AdminUserSummary> users,
        long total,
        int page,
        int size
) {
}
