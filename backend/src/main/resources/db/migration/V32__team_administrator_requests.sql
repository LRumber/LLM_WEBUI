CREATE TABLE team_administrator_request (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    review_note VARCHAR(1000),
    reviewer VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX idx_team_administrator_request_pending
    ON team_administrator_request(user_id) WHERE status = 'pending';
