package com.lmplatform.auth.model;

import java.io.Serializable;

/** Local identity returned after OAuth2 userInfo has been synchronized. */
public record AuthenticatedShadowUser(
        long id,
        long externalUserId,
        String idNo,
        String accountNumber,
        String displayName,
        String userNumber,
        String email,
        boolean administrator,
        boolean teamAdministrator
) implements Serializable {
}
