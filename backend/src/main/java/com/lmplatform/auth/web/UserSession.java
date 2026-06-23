package com.lmplatform.auth.web;

/** Session keys shared by OAuth2 login and current-user repository boundaries. */
public final class UserSession {

    public static final String USER_ID_KEY = "platform_user_id";
    public static final String USER_KEY = "platform_user";

    private UserSession() {
    }
}
