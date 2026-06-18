package com.lmplatform.auth.web;

import com.lmplatform.auth.config.OAuth2GatewayProperties;
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

    public AuthController(OAuth2GatewayProperties properties) {
        this.properties = properties;
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

        // Token exchange and shadow-user synchronization are implemented in the next auth slice.
        return ResponseEntity.status(501)
                .body(new AuthError("oauth_exchange_not_implemented", "OAuth2 token exchange is not implemented yet"));
    }

    private record AuthError(String code, String message) {
    }
}
