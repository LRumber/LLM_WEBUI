-- Team administration is intentionally independent from system administration.
ALTER TABLE app_user
    ADD COLUMN is_team_admin BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_app_user_team_admin ON app_user (is_team_admin) WHERE is_team_admin = TRUE;

CREATE TABLE team_space (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    description         VARCHAR(500),
    owner_user_id       BIGINT NOT NULL REFERENCES app_user (id),
    status              VARCHAR(16) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active', 'disabled', 'deleted')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE team_member (
    team_id             UUID NOT NULL REFERENCES team_space (id) ON DELETE CASCADE,
    user_id             BIGINT NOT NULL REFERENCES app_user (id),
    member_role         VARCHAR(16) NOT NULL
                        CHECK (member_role IN ('owner', 'admin', 'member')),
    added_by_user_id    BIGINT NOT NULL REFERENCES app_user (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (team_id, user_id)
);

CREATE INDEX idx_team_member_user ON team_member (user_id, team_id);
