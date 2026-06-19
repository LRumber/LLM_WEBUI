package com.lmplatform.admin.config;

import com.lmplatform.admin.web.AdminSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Rejects administration APIs unless a local or OAuth2 administrator session is present. */
@Component
public class AdminAuthenticationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getSession(false) != null
                && request.getSession(false).getAttribute(AdminSession.PRINCIPAL_KEY) != null) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }
}
