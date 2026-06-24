package com.lmplatform.admin.web;

import com.lmplatform.admin.model.AdminPrincipal;
import com.lmplatform.admin.service.AdminAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Establishes and clears local administrator sessions. */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthenticationService authenticationService;

    public AdminAuthController(AdminAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody AdminLoginRequest credentials,
            HttpServletRequest request
    ) {
        return authenticationService.authenticate(credentials.username(), credentials.password())
                .<ResponseEntity<?>>map(principal -> {
                    HttpSession session = request.getSession(true);
                    request.changeSessionId();
                    session.setMaxInactiveInterval(AdminSession.MAX_INACTIVE_SECONDS);
                    session.setAttribute(AdminSession.PRINCIPAL_KEY, principal);
                    return ResponseEntity.ok(principal);
                })
                .orElseGet(() -> ResponseEntity.status(401).body(
                        new LoginError("invalid_credentials", "用户名或密码错误")
                ));
    }

    @GetMapping("/me")
    public AdminPrincipal me(HttpSession session) {
        return (AdminPrincipal) session.getAttribute(AdminSession.PRINCIPAL_KEY);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.noContent().build();
    }

    private record LoginError(String code, String message) {
    }
}
