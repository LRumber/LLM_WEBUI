package com.lmplatform.api.web;

import com.lmplatform.api.model.ApiPage;
import com.lmplatform.api.repository.ApiCreditRequestRepository;
import com.lmplatform.api.repository.ApiCallLogRepository;
import com.lmplatform.api.service.ApiGatewayException;
import com.lmplatform.auth.web.UserSession;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/developer")
public class DeveloperActivityController {
    private final ApiCreditRequestRepository requests;
    private final ApiCallLogRepository logs;
    public DeveloperActivityController(ApiCreditRequestRepository requests, ApiCallLogRepository logs) { this.requests = requests; this.logs = logs; }

    @PostMapping("/credit-requests")
    public Map<String, UUID> submit(HttpSession session, @Valid @RequestBody CreateRequest request) {
        return Map.of("id", requests.submit(userId(session), request.type(), request.amount(), request.reason()));
    }

    @GetMapping("/credit-requests")
    public ApiPage<ApiCreditRequestRepository.RequestRow> requests(HttpSession session,
            @RequestParam(defaultValue="all") String status, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return requests.list(userId(session), status, page, size);
    }

    @GetMapping("/call-logs")
    public ApiPage<ApiCallLogRepository.LogRow> logs(HttpSession session,
            @RequestParam(defaultValue="all") String status, @RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="") String model, @RequestParam(defaultValue="") String from,
            @RequestParam(defaultValue="") String to, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return logs.list(userId(session), status, q, model, from, to, page, size);
    }

    private long userId(HttpSession session) {
        if (session.getAttribute(UserSession.USER_ID_KEY) instanceof Number id) return id.longValue();
        throw new ApiGatewayException("unauthorized", "请先登录", 401);
    }

    public record CreateRequest(@NotBlank String type, @Min(0) long amount, @NotBlank @Size(max=1000) String reason) {}
}
