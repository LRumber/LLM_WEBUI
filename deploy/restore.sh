#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
BACKUP_NAME="${1:-}"
[[ -n "$BACKUP_NAME" ]] || { echo "Usage: $0 <backup-directory-name>" >&2; exit 2; }
SOURCE="$DEPLOY_DIR/backups/$BACKUP_NAME"
[[ -f "$SOURCE/database.dump" ]] || { echo "Backup not found: $SOURCE" >&2; exit 1; }

read -r -p "Restore $BACKUP_NAME and replace current platform data? [y/N] " answer
[[ "$answer" == "y" || "$answer" == "Y" ]] || exit 1

COMPOSE=(docker compose --env-file "$ROOT_DIR/.env")
[[ -f "$DEPLOY_DIR/runtime.env" ]] && COMPOSE+=(--env-file "$DEPLOY_DIR/runtime.env")
COMPOSE+=(-f "$DEPLOY_DIR/docker-compose.yml")

"${COMPOSE[@]}" stop gateway backend ai-service
cat "$SOURCE/database.dump" | "${COMPOSE[@]}" exec -T postgres sh -c \
  'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner'

if [[ -d "$SOURCE/minio" ]]; then
  "${COMPOSE[@]}" --profile tools run --rm --entrypoint /bin/sh minio-client -c \
    "mc alias set local http://minio:9000 \"\$MINIO_ROOT_USER\" \"\$MINIO_ROOT_PASSWORD\" >/dev/null && mc mb --ignore-existing local/\"\$MINIO_BUCKET\" >/dev/null && mc mirror --overwrite /backup/$BACKUP_NAME/minio local/\"\$MINIO_BUCKET\""
fi

if [[ -f "$SOURCE/ai-config.tar.gz" ]]; then
  "${COMPOSE[@]}" --profile tools run --rm --entrypoint /bin/sh volume-tool -c \
    "rm -rf /source/* /source/.[!.]* /source/..?* 2>/dev/null || true; tar -xzf /backup/$BACKUP_NAME/ai-config.tar.gz -C /source"
fi

"${COMPOSE[@]}" up -d ai-service backend gateway
echo "Restore completed"
