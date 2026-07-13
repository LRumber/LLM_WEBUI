#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
BUNDLE_DIR="$DEPLOY_DIR/bundles"
mkdir -p "$BUNDLE_DIR"

COMPOSE=(docker compose --env-file "$ROOT_DIR/.env" -f "$DEPLOY_DIR/docker-compose.yml")
"${COMPOSE[@]}" --profile tools pull postgres redis minio minio-client volume-tool
"${COMPOSE[@]}" build backend ai-service gateway
mapfile -t images < <("${COMPOSE[@]}" --profile tools config --images | sort -u)
docker save -o "$BUNDLE_DIR/lm-platform-images-x86_64.tar" "${images[@]}"
(cd "$BUNDLE_DIR" && sha256sum "lm-platform-images-x86_64.tar" > SHA256SUMS)
echo "Offline image bundle created in $BUNDLE_DIR"
