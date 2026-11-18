package com.lmplatform.api.service;

/** Safe diagnostic values captured while holding the account quota lock. */
public class InsufficientApiCreditException extends ApiGatewayException {
    private final long required;
    private final long available;

    public InsufficientApiCreditException(long required, long available, String estimate) {
        super("quota_exceeded", "本次预计需预占 " + required + " Token，当前可用 "
                + Math.max(0, available) + " Token（" + estimate + "）。", 429);
        this.required = required;
        this.available = Math.max(0, available);
    }

    public long required() { return required; }
    public long available() { return available; }
}
