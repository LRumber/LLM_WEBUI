ALTER TABLE platform_announcement
    ADD COLUMN version_label VARCHAR(64);

WITH ordered AS (
    SELECT
        id,
        row_number() OVER (ORDER BY COALESCE(published_at, updated_at), created_at) AS version_number
    FROM platform_announcement
)
UPDATE platform_announcement announcement
SET version_label = 'V' || ordered.version_number
FROM ordered
WHERE announcement.id = ordered.id;

ALTER TABLE platform_announcement
    ALTER COLUMN version_label SET NOT NULL;
