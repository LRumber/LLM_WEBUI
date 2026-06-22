package com.lmplatform.auth.model;

/** Local identity returned after OAuth2 userInfo has been synchronized. */
public record AuthenticatedShadowUser(
        long id,
        String accountNumber,
        String displayName,
        boolean administrator,
        boolean teamAdministrator
) {
}
