CREATE TABLE api_user_quota (
    user_id             BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    total_token_quota   BIGINT NOT NULL DEFAULT 0 CHECK (total_token_quota >= 0),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE api_key (
    id                  UUID PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name                VARCHAR(128) NOT NULL,
    key_prefix          VARCHAR(32) NOT NULL,
    key_hash            VARCHAR(128) NOT NULL UNIQUE,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at        TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ
);

CREATE INDEX idx_api_key_user ON api_key (user_id, created_at DESC);
CREATE INDEX idx_api_key_prefix ON api_key (key_prefix);

CREATE TABLE api_model_access (
    model_key           VARCHAR(128) PRIMARY KEY,
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE api_usage_event (
    id                  BIGSERIAL PRIMARY KEY,
    request_id          UUID NOT NULL UNIQUE,
    user_id             BIGINT NOT NULL REFERENCES app_user (id),
    api_key_id          UUID REFERENCES api_key (id),
    model_key           VARCHAR(128) NOT NULL,
    endpoint            VARCHAR(128) NOT NULL,
    input_tokens        INTEGER NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens       INTEGER NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    total_tokens        INTEGER GENERATED ALWAYS AS (input_tokens + output_tokens) STORED,
    status              VARCHAR(32) NOT NULL,
    error_code          VARCHAR(128),
    duration_ms         INTEGER,
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_api_usage_user_time ON api_usage_event (user_id, occurred_at DESC);
CREATE INDEX idx_api_usage_model_time ON api_usage_event (model_key, occurred_at DESC);

CREATE TABLE daily_api_usage (
    usage_date          DATE NOT NULL,
    user_id             BIGINT NOT NULL REFERENCES app_user (id),
    request_count       INTEGER NOT NULL DEFAULT 0,
    input_tokens        BIGINT NOT NULL DEFAULT 0,
    output_tokens       BIGINT NOT NULL DEFAULT 0,
    last_aggregated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (usage_date, user_id)
);
