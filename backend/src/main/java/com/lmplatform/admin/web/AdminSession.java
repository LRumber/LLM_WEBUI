package com.lmplatform.admin.web;

/** Shared session contract for both local and OAuth2 administrator identities. */
public final class AdminSession {

    public static final String PRINCIPAL_KEY = "admin_principal";
    public static final int MAX_INACTIVE_SECONDS = 60 * 60;

    private AdminSession() {
    }
}
