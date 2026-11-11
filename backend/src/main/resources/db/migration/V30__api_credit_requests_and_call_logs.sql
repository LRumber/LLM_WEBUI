CREATE TABLE api_credit_request (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    type VARCHAR(16) NOT NULL CHECK (type IN ('tokens', 'unlimited')),
    requested_tokens BIGINT NOT NULL CHECK (requested_tokens >= 0),
    approved_tokens BIGINT,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    review_note VARCHAR(1000),
    reviewer VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX idx_api_credit_request_pending ON api_credit_request(user_id) WHERE status = 'pending';
CREATE INDEX idx_api_credit_request_status ON api_credit_request(status, created_at DESC);
CREATE INDEX idx_api_credit_request_user ON api_credit_request(user_id, created_at DESC);

-- Diagnostic records deliberately do not reference the billing aggregates.
CREATE TABLE api_call_log (
    request_id UUID PRIMARY KEY,
    user_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    api_key_id UUID REFERENCES api_key(id) ON DELETE SET NULL,
    model VARCHAR(128) NOT NULL DEFAULT '',
    endpoint VARCHAR(128) NOT NULL,
    stream BOOLEAN NOT NULL DEFAULT FALSE,
    http_status INTEGER NOT NULL,
    upstream_status INTEGER,
    status VARCHAR(16) NOT NULL CHECK (status IN ('success', 'failed')),
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    detail VARCHAR(1000),
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    estimated BOOLEAN NOT NULL DEFAULT FALSE,
    duration_ms BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_api_call_log_user ON api_call_log(user_id, created_at DESC);
CREATE INDEX idx_api_call_log_status ON api_call_log(status, created_at DESC);
CREATE INDEX idx_api_call_log_time ON api_call_log(created_at DESC);
CREATE INDEX idx_api_call_log_model ON api_call_log(model, created_at DESC);
