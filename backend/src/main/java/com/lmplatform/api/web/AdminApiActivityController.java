package com.lmplatform.api.web;

import com.lmplatform.api.model.ApiPage;
import com.lmplatform.api.repository.ApiCreditRequestRepository;
import com.lmplatform.api.repository.ApiCallLogRepository;
import com.lmplatform.api.service.ApiGatewayException;
import com.lmplatform.admin.model.AdminPrincipal;
import com.lmplatform.admin.web.AdminSession;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/api")
public class AdminApiActivityController {
    private final ApiCreditRequestRepository requests;
    private final ApiCallLogRepository logs;
    public AdminApiActivityController(ApiCreditRequestRepository requests, ApiCallLogRepository logs) { this.requests = requests; this.logs = logs; }

    @GetMapping("/credit-requests")
    public ApiPage<ApiCreditRequestRepository.RequestRow> requests(@RequestParam(defaultValue="all") String status,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return requests.list(null, status, page, size);
    }

    @PostMapping("/credit-requests/{id}/review")
    public ResponseEntity<Void> review(HttpSession session, @PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        if (!(session.getAttribute(AdminSession.PRINCIPAL_KEY) instanceof AdminPrincipal admin)) {
            throw new ApiGatewayException("unauthorized", "请使用管理员账号登录", 401);
        }
        requests.review(id, request.approved(), request.approvedTokens(), request.note(), admin.source() + ":" + admin.id() + ":" + admin.displayName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/call-logs")
    public ApiPage<ApiCallLogRepository.LogRow> logs(@RequestParam(defaultValue="all") String status,
            @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String model,
            @RequestParam(defaultValue="") String from, @RequestParam(defaultValue="") String to,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return logs.list(null, status, q, model, from, to, page, size);
    }

    public record ReviewRequest(@NotNull Boolean approved, @Min(0) long approvedTokens, @Size(max=1000) String note) {}
}
