package com.lmplatform.api.service;

import com.lmplatform.api.model.ApiPrincipal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request-local metadata only: never retains request bodies, response text or credentials. */
public final class ApiCallTrace {
    private static final ThreadLocal<ApiCallTrace> CURRENT = new ThreadLocal<>();
    public final UUID requestId = UUID.randomUUID();
    public final OffsetDateTime createdAt = OffsetDateTime.now();
    public final long started = System.nanoTime();
    public Long userId;
    public UUID keyId;
    public String model = "";
    public boolean stream;
    public long inputTokens;
    public long outputTokens;
    public boolean estimated;
    public ApiFailure failure;

    public static ApiCallTrace begin() { var trace = new ApiCallTrace(); CURRENT.set(trace); return trace; }
    public static ApiCallTrace current() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
    public static void identify(ApiPrincipal principal) {
        var trace = current();
        if (trace != null) { trace.userId = principal.userId(); trace.keyId = principal.keyId(); }
    }
    public static void request(String model, boolean stream) {
        var trace = current();
        if (trace != null) { trace.model = model == null ? "" : model.substring(0, Math.min(128, model.length())); trace.stream = stream; }
    }
    public static void failed(Throwable exception) {
        var trace = current();
        if (trace != null && trace.failure == null) trace.failure = ApiFailure.from(exception);
    }
    public static void usage(int input, int output, boolean estimated) {
        var trace = current();
        if (trace != null) { trace.inputTokens = input; trace.outputTokens = output; trace.estimated = estimated; }
    }
}
