CREATE TABLE app_user (
    id                  BIGSERIAL PRIMARY KEY,
    external_user_id    BIGINT NOT NULL UNIQUE,
    account_number      VARCHAR(128) NOT NULL,
    display_name        VARCHAR(128) NOT NULL,
    user_number         VARCHAR(128),
    tenant_id           BIGINT,
    email               VARCHAR(255),
    telephone           VARCHAR(64),
    avatar_path         VARCHAR(512),
    status              SMALLINT NOT NULL DEFAULT 1,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_app_user_tenant ON app_user (tenant_id);
CREATE INDEX idx_app_user_account ON app_user (account_number);

CREATE TABLE login_event (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT REFERENCES app_user (id),
    external_user_id    BIGINT,
    session_id          VARCHAR(128),
    event_type          VARCHAR(32) NOT NULL,
    success             BOOLEAN NOT NULL,
    failure_reason      VARCHAR(512),
    ip_address          VARCHAR(64),
    user_agent          VARCHAR(1024),
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_login_event_user_time ON login_event (user_id, occurred_at DESC);
CREATE INDEX idx_login_event_time ON login_event (occurred_at DESC);

CREATE TABLE conversation (
    id                  UUID PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES app_user (id),
    team_id             BIGINT,
    model_key           VARCHAR(128) NOT NULL,
    title               VARCHAR(255),
    message_count       INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_conversation_user_time ON conversation (user_id, updated_at DESC);

CREATE TABLE usage_event (
    id                  BIGSERIAL PRIMARY KEY,
    request_id          UUID NOT NULL UNIQUE,
    user_id             BIGINT NOT NULL REFERENCES app_user (id),
    team_id             BIGINT,
    conversation_id     UUID REFERENCES conversation (id),
    model_key           VARCHAR(128) NOT NULL,
    application_key     VARCHAR(128),
    input_tokens        INTEGER NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens       INTEGER NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    total_tokens        INTEGER GENERATED ALWAYS AS (input_tokens + output_tokens) STORED,
    first_token_ms      INTEGER,
    duration_ms         INTEGER,
    status              VARCHAR(32) NOT NULL,
    error_code          VARCHAR(128),
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_usage_event_user_time ON usage_event (user_id, occurred_at DESC);
CREATE INDEX idx_usage_event_model_time ON usage_event (model_key, occurred_at DESC);

CREATE TABLE daily_user_usage (
    usage_date          DATE NOT NULL,
    user_id             BIGINT NOT NULL REFERENCES app_user (id),
    conversation_count  INTEGER NOT NULL DEFAULT 0,
    request_count       INTEGER NOT NULL DEFAULT 0,
    input_tokens        BIGINT NOT NULL DEFAULT 0,
    output_tokens       BIGINT NOT NULL DEFAULT 0,
    login_count         INTEGER NOT NULL DEFAULT 0,
    last_aggregated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (usage_date, user_id)
);
