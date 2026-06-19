package com.lmplatform.admin.model;

import java.io.Serializable;

/** Session-safe identity shared by local and future OAuth2 administrator logins. */
public record AdminPrincipal(long id, String username, String displayName, String source)
        implements Serializable {
}
