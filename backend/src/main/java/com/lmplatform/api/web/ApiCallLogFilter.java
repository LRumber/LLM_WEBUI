package com.lmplatform.api.web;

import com.lmplatform.api.repository.ApiCallLogRepository;
import com.lmplatform.api.service.ApiCallTrace;
import com.lmplatform.api.service.ApiFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiCallLogFilter extends OncePerRequestFilter {
    private final ApiCallLogRepository logs;
    public ApiCallLogFilter(ApiCallLogRepository logs) { this.logs = logs; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/v1/"); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var trace = ApiCallTrace.begin();
        boolean unhandledFailure = false;
        response.setHeader("X-Request-ID", trace.requestId.toString());
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            unhandledFailure = true;
            ApiCallTrace.failed(exception);
            throw exception;
        } finally {
            try {
                if (trace.failure == null && response.getStatus() >= 400) {
                    String code = switch (response.getStatus()) {
                        case 400, 405, 413, 415 -> "invalid_request";
                        case 401 -> "invalid_api_key";
                        case 404 -> "not_found";
                        default -> "upstream_error";
                    };
                    trace.failure = ApiFailure.of(code, null, "HTTP " + response.getStatus());
                }
                String path = request.getRequestURI();
                String endpoint = java.util.Set.of("/api/v1/models", "/api/v1/chat/completions", "/api/v1/embeddings",
                        "/api/v1/rerank", "/api/v1/audio/transcriptions").contains(path) ? path : "/api/v1/unknown";
                logs.record(trace, endpoint, unhandledFailure && !response.isCommitted() && response.getStatus() < 400
                        ? 500 : response.getStatus());
            } catch (RuntimeException exception) {
                // A diagnostic database outage must not replay or interrupt the actual model request.
                LoggerFactory.getLogger(ApiCallLogFilter.class).warn("API diagnostic log could not be saved; requestId={}, cause={}",
                        trace.requestId, exception.getClass().getSimpleName());
            } finally { ApiCallTrace.clear(); }
        }
    }
}
