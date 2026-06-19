package com.lmplatform.admin.model;

/** Internal credential projection used only during local administrator authentication. */
public record LocalAdminCredential(
        long id,
        String username,
        String passwordHash,
        String displayName,
        short status
) {
}
