ALTER TABLE api_usage_event ADD COLUMN estimated BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE api_credit_adjustment (
    id BIGSERIAL PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    actor VARCHAR(255) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    note VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_api_credit_adjustment_user ON api_credit_adjustment(user_id, created_at DESC);

CREATE TABLE api_credit_reservation (
    request_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    tokens BIGINT NOT NULL CHECK (tokens >= 0),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '15 minutes'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_api_credit_reservation_user ON api_credit_reservation(user_id);
