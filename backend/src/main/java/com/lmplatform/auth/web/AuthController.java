package com.lmplatform.auth.web;

import com.lmplatform.auth.config.OAuth2GatewayProperties;
import com.lmplatform.auth.service.OAuth2LoginService;
import com.lmplatform.admin.model.AdminPrincipal;
import com.lmplatform.admin.web.AdminSession;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Starts and validates the custom OAuth2 authorization-code flow. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String OAUTH_STATE_SESSION_KEY = "oauth2_state";

    private final OAuth2GatewayProperties properties;
    private final OAuth2LoginService loginService;

    public AuthController(OAuth2GatewayProperties properties, OAuth2LoginService loginService) {
        this.properties = properties;
        this.loginService = loginService;
    }

    /** Redirects the browser to the identity gateway with a session-bound CSRF state value. */
    @GetMapping("/login")
    public ResponseEntity<Void> login(HttpSession session) {
        String state = UUID.randomUUID().toString();
        session.setAttribute(OAUTH_STATE_SESSION_KEY, state);

        URI authorizeUri = UriComponentsBuilder.fromUriString(properties.authorizeEndpoint())
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .build()
                .encode()
                .toUri();

        return ResponseEntity.status(302).location(authorizeUri).build();
    }

    /** Validates the returned state before token exchange and shadow-user synchronization. */
    @GetMapping("/oauth2/callback")
    public ResponseEntity<?> callback(
            @RequestParam String code,
            @RequestParam String state,
            HttpSession session
    ) {
        Object expectedState = session.getAttribute(OAUTH_STATE_SESSION_KEY);
        session.removeAttribute(OAUTH_STATE_SESSION_KEY);
        if (expectedState == null || !expectedState.equals(state)) {
            return ResponseEntity.badRequest().body(new AuthError("invalid_state", "OAuth2 state validation failed"));
        }

        try {
            var user = loginService.authenticate(code);
            session.setAttribute(UserSession.USER_ID_KEY, user.id());
            if (user.administrator()) {
                session.setAttribute(AdminSession.PRINCIPAL_KEY, new AdminPrincipal(
                        user.id(), user.accountNumber(), user.displayName(), "oauth2"
                ));
            }
            return ResponseEntity.status(302).location(URI.create("/app")).build();
        } catch (Exception exception) {
            return ResponseEntity.status(502).body(new AuthError(
                    "oauth_exchange_failed", "OAuth2 login failed"
            ));
        }
    }

    private record AuthError(String code, String message) {
    }
}
