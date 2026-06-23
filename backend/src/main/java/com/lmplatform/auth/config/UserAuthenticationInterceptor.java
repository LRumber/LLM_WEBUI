package com.lmplatform.auth.config;

import com.lmplatform.auth.web.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Requires an OAuth2-synchronized user session for private workspace APIs. */
@Component
public class UserAuthenticationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var session = request.getSession(false);
        if (session != null && session.getAttribute(UserSession.USER_ID_KEY) instanceof Number) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }
}
