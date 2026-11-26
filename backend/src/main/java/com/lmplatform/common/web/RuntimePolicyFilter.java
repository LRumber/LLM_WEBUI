package com.lmplatform.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.api.service.ApiGatewayService;
import com.lmplatform.api.service.ApiGatewayException;
import com.lmplatform.api.service.ApiCallTrace;
import com.lmplatform.auth.web.UserSession;
import com.lmplatform.common.service.RuntimePolicyService;
import com.lmplatform.common.service.RuntimeAdmissionService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

public class RuntimePolicyFilter extends OncePerRequestFilter implements AutoCloseable {
    private final RuntimePolicyService policies;
    private final RuntimeAdmissionService admission;
    private final ApiGatewayService api;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "runtime-lease-renewal"); t.setDaemon(true); return t;
    });
    public RuntimePolicyFilter(RuntimePolicyService policies, RuntimeAdmissionService admission, ApiGatewayService api, ObjectMapper mapper) {
        this.policies = policies; this.admission = admission; this.api = api; this.mapper = mapper;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !isChat(path) && !isApi(path)
                && !path.equals("/api/files") && !path.startsWith("/api/speech/");
    }
    private boolean isChat(String path) {
        return path.equals("/api/chat/stream") || path.matches("/api/conversations/[^/]+/messages/[^/]+/(regenerate|edit-and-regenerate)");
    }
    private boolean isApi(String path) { return Set.of("/api/v1/chat/completions", "/api/v1/embeddings", "/api/v1/rerank", "/api/v1/audio/transcriptions").contains(path); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        UUID lease;
        try {
            Long userId;
            if (isApi(path)) userId = api.authenticate(request.getHeader("Authorization")).userId();
            else {
                var session = request.getSession(false);
                Object value = session == null ? null : session.getAttribute(UserSession.USER_ID_KEY);
                userId = value instanceof Number number ? number.longValue() : null;
            }
            if (userId == null) { chain.doFilter(request, response); return; }
            var policy = policies.current();
            if (policy.maintenance()) { error(response, 503, "maintenance", policy.maintenanceMessage()); return; }
            if (!isChat(path) && !isApi(path)) { chain.doFilter(request, response); return; }
            lease = admission.acquire(userId, isChat(path), policy);
        } catch (ResponseStatusException ex) { error(response, ex.getStatusCode().value(), "request_limit", ex.getReason()); return; }
        catch (ApiGatewayException ex) { error(response, ex.status(), ex.code(), ex.getMessage()); return; }
        AtomicBoolean released = new AtomicBoolean();
        var renewal = heartbeat.scheduleWithFixedDelay(() -> {
            try { if (!released.get()) admission.renew(lease); }
            catch (RuntimeException ex) { logger.warn("Runtime request lease renewal failed"); }
        }, 60, 60, TimeUnit.SECONDS);
        Runnable release = () -> {
            if (!released.compareAndSet(false, true)) return;
            renewal.cancel(false);
            try { admission.release(lease); }
            catch (RuntimeException ex) { logger.warn("Runtime request lease release failed; expiry will recover it"); }
        };
        try { chain.doFilter(request, response); }
        finally {
            if (request.isAsyncStarted()) {
                try { request.getAsyncContext().addListener(new AsyncListener() {
                    public void onComplete(AsyncEvent e) { release.run(); }
                    public void onError(AsyncEvent e) { release.run(); }
                    public void onTimeout(AsyncEvent e) { release.run(); }
                    public void onStartAsync(AsyncEvent e) { e.getAsyncContext().addListener(this); }
                }); } catch (IllegalStateException ex) { release.run(); }
            } else release.run();
        }
    }
    private void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        ApiCallTrace.failed(new ApiGatewayException(code, message, status));
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        if (status == 429 || status == 503) response.setHeader("Retry-After", "60");
        mapper.writeValue(response.getWriter(), Map.of("message", message, "error", Map.of("message", message, "code", code, "type", code)));
    }
    @Override public void close() { heartbeat.shutdownNow(); }
    @Override public void destroy() { close(); }
}
