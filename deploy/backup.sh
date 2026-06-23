#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET="$DEPLOY_DIR/backups/$STAMP"
mkdir -p "$TARGET"

COMPOSE=(docker compose --env-file "$ROOT_DIR/.env")
[[ -f "$DEPLOY_DIR/runtime.env" ]] && COMPOSE+=(--env-file "$DEPLOY_DIR/runtime.env")
COMPOSE+=(-f "$DEPLOY_DIR/docker-compose.yml")

"${COMPOSE[@]}" exec -T postgres sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$TARGET/database.dump"

"${COMPOSE[@]}" --profile tools run --rm --entrypoint /bin/sh minio-client -c \
  "mc alias set local http://minio:9000 \"\$MINIO_ROOT_USER\" \"\$MINIO_ROOT_PASSWORD\" >/dev/null && mc mb --ignore-existing local/\"\$MINIO_BUCKET\" >/dev/null && mc mirror --overwrite local/\"\$MINIO_BUCKET\" /backup/$STAMP/minio"

"${COMPOSE[@]}" --profile tools run --rm --entrypoint /bin/sh volume-tool -c \
  "tar -czf /backup/$STAMP/ai-config.tar.gz -C /source ."

cp "$ROOT_DIR/.env" "$TARGET/env.snapshot"
[[ -f "$DEPLOY_DIR/runtime.env" ]] && cp "$DEPLOY_DIR/runtime.env" "$TARGET/runtime.env"
chmod 600 "$TARGET/env.snapshot" "$TARGET/database.dump"
echo "Backup created at $TARGET"
