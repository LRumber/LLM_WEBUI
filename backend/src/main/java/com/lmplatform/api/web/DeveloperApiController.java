package com.lmplatform.api.web;

import com.lmplatform.api.model.ApiDeveloperSummary;
import com.lmplatform.api.model.ApiKeyCreateRequest;
import com.lmplatform.api.model.ApiKeyCreateResponse;
import com.lmplatform.api.model.ApiKeySummary;
import com.lmplatform.api.service.ApiGatewayService;
import com.lmplatform.auth.web.UserSession;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/developer")
public class DeveloperApiController {

    private final ApiGatewayService service;

    public DeveloperApiController(ApiGatewayService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiDeveloperSummary summary(HttpSession session) {
        return service.developerSummary(userId(session));
    }

    @GetMapping("/keys")
    public List<ApiKeySummary> keys(HttpSession session) {
        return service.keys(userId(session));
    }

    @PostMapping("/keys")
    public ApiKeyCreateResponse createKey(
            HttpSession session,
            @Valid @RequestBody ApiKeyCreateRequest request
    ) {
        return service.createKey(userId(session), request.name());
    }

    @PostMapping("/keys/{keyId}/reset")
    public ApiKeyCreateResponse resetKey(HttpSession session, @PathVariable UUID keyId) {
        return service.resetKey(userId(session), keyId);
    }

    private long userId(HttpSession session) {
        Object value = session.getAttribute(UserSession.USER_ID_KEY);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("User session is missing");
    }
}
