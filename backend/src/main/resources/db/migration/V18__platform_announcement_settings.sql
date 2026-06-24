INSERT INTO system_setting (setting_key, setting_value, description)
VALUES
    ('platform_announcement_revision', '0', 'Monotonic revision for the user-facing product announcement.'),
    ('platform_announcement_title', '', 'Title of the user-facing product announcement.'),
    ('platform_announcement_body', '', 'Body of the user-facing product announcement.'),
    ('platform_announcement_published', 'false', 'Whether the product announcement is visible to users.')
ON CONFLICT (setting_key) DO NOTHING;
