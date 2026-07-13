CREATE TABLE platform_announcement (
    id              UUID PRIMARY KEY,
    title           VARCHAR(120) NOT NULL,
    body            TEXT NOT NULL,
    published       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_platform_announcement_publish_time ON platform_announcement (published, published_at DESC);

INSERT INTO platform_announcement (id, title, body, published, published_at)
SELECT
    gen_random_uuid(),
    title.setting_value,
    body.setting_value,
    published.setting_value = 'true',
    CASE WHEN published.setting_value = 'true' THEN CURRENT_TIMESTAMP ELSE NULL END
FROM system_setting title
JOIN system_setting body ON body.setting_key = 'platform_announcement_body'
JOIN system_setting published ON published.setting_key = 'platform_announcement_published'
WHERE title.setting_key = 'platform_announcement_title'
  AND title.setting_value <> ''
  AND body.setting_value <> '';
