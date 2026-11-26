-- Preserve all existing users' formerly implicit balances before making the default configurable.
INSERT INTO api_user_quota(user_id, total_token_quota)
SELECT id, 10000000 FROM app_user ON CONFLICT DO NOTHING;

CREATE FUNCTION platform_default_api_quota() RETURNS BIGINT LANGUAGE SQL STABLE AS $$
    SELECT COALESCE((SELECT (setting_value::jsonb ->> 'defaultApiTokens')::bigint
                    FROM system_setting WHERE setting_key = 'runtime_policy'), 10000000)
$$;
ALTER TABLE api_user_quota ALTER COLUMN total_token_quota SET DEFAULT platform_default_api_quota();
CREATE FUNCTION initialize_user_api_quota() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO api_user_quota(user_id) VALUES (NEW.id) ON CONFLICT DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER initialize_user_api_quota AFTER INSERT ON app_user
FOR EACH ROW EXECUTE FUNCTION initialize_user_api_quota();

CREATE TABLE runtime_daily_requests (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    usage_date DATE NOT NULL,
    requests BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(user_id, usage_date)
);
CREATE TABLE runtime_request_lease (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '15 minutes'
);
CREATE INDEX idx_runtime_request_lease_user ON runtime_request_lease(user_id, expires_at);
