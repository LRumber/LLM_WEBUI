package com.lmplatform.auth.web;

import com.lmplatform.auth.config.OAuth2GatewayProperties;
import com.lmplatform.auth.repository.ShadowUserRepository;
import com.lmplatform.auth.service.OAuth2LoginService;
import com.lmplatform.chat.service.LegacyChatImportService;
import com.lmplatform.admin.model.AdminPrincipal;
import com.lmplatform.admin.web.AdminSession;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.util.UriComponentsBuilder;

/** Starts and validates the custom OAuth2 authorization-code flow. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String OAUTH_STATE_SESSION_KEY = "oauth2_state";

    private final OAuth2GatewayProperties properties;
    private final OAuth2LoginService loginService;
    private final ShadowUserRepository shadowUserRepository;
    private final LegacyChatImportService legacyChatImportService;
    private final boolean devLoginEnabled;

    public AuthController(
            OAuth2GatewayProperties properties,
            OAuth2LoginService loginService,
            ShadowUserRepository shadowUserRepository,
            LegacyChatImportService legacyChatImportService,
            @Value("${platform.auth.dev-login-enabled:false}") boolean devLoginEnabled
    ) {
        this.properties = properties;
        this.loginService = loginService;
        this.shadowUserRepository = shadowUserRepository;
        this.legacyChatImportService = legacyChatImportService;
        this.devLoginEnabled = devLoginEnabled;
    }

    /** Exposes non-secret authentication UI switches to the browser. */
    @GetMapping("/options")
    public AuthOptions options() {
        return new AuthOptions(devLoginEnabled);
    }

    /** Redirects the browser to the identity gateway using the provider-compatible authorization request. */
    @GetMapping("/login")
    public ResponseEntity<Void> login(HttpSession session) {
        String state = UUID.randomUUID().toString();
        session.setAttribute(OAUTH_STATE_SESSION_KEY, state);

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.authorizeEndpoint())
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code");
        if (properties.sendState()) {
            builder.queryParam("state", state);
        }
        URI authorizeUri = builder
                .build()
                .encode()
                .toUri();

        return ResponseEntity.status(302).location(authorizeUri).build();
    }

    /** Validates the returned state before token exchange and shadow-user synchronization. */
    @GetMapping("/oauth2/callback")
    public ResponseEntity<?> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpSession session
    ) {
        if (error != null && !error.isBlank()) {
            return ResponseEntity.badRequest().body(new AuthError("oauth_error", error));
        }
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(new AuthError("missing_code", "OAuth2 callback did not contain code"));
        }
        if (properties.sendState()) {
            Object expectedState = session.getAttribute(OAUTH_STATE_SESSION_KEY);
            session.removeAttribute(OAUTH_STATE_SESSION_KEY);
            if (expectedState == null || !expectedState.equals(state)) {
                return ResponseEntity.badRequest().body(new AuthError("invalid_state", "OAuth2 state validation failed"));
            }
        }

        try {
            var user = loginService.authenticate(code);
            session.setAttribute(UserSession.USER_ID_KEY, user.id());
            session.setAttribute(UserSession.USER_KEY, user);
            legacyChatImportService.importAfterLogin(user);
            if (user.administrator()) {
                session.setMaxInactiveInterval(AdminSession.MAX_INACTIVE_SECONDS);
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

    /** Returns the synchronized identity snapshot stored after a successful OAuth2 login. */
    @GetMapping("/me")
    public ResponseEntity<?> currentUser(HttpSession session) {
        Object user = session.getAttribute(UserSession.USER_KEY);
        return user == null ? ResponseEntity.status(401).build() : ResponseEntity.ok(user);
    }

    /** Creates a deterministic local-only user session for feature development without the identity gateway. */
    @PostMapping("/dev-login")
    public ResponseEntity<?> devLogin(
            @RequestParam(defaultValue = "DEV001") String user,
            HttpSession session
    ) {
        if (!devLoginEnabled) {
            return ResponseEntity.status(404).body(new AuthError("dev_login_disabled", "Local development login is disabled"));
        }
        boolean member = "DEV002".equalsIgnoreCase(user) || "dev-member".equalsIgnoreCase(user);
        var loginUser = shadowUserRepository.upsert(
                member ? 900000002L : 900000001L,
                member ? "DEV-TEAM-MEMBER" : "DEV-LOCAL-USER",
                member ? "dev-member" : "dev-local",
                member ? "本机调试成员" : "本机调试用户",
                member ? "DEV002" : "DEV001",
                1L,
                (short) 1,
                member ? "dev-member" : "dev-local",
                member ? "dev-member@example.test" : "dev-local@example.test",
                null,
                null,
                (short) 1
        );
        session.setAttribute(UserSession.USER_ID_KEY, loginUser.id());
        session.setAttribute(UserSession.USER_KEY, loginUser);
        legacyChatImportService.importAfterLogin(loginUser);
        return ResponseEntity.ok(loginUser);
    }

    /** Clears both the user and optional administrator principals from the local session. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.noContent().build();
    }

    private record AuthError(String code, String message) {
    }

    private record AuthOptions(boolean devLoginEnabled) {
    }
}
