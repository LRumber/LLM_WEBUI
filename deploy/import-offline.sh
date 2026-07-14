#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"

normalize_text_file() {
  local file="$1"
  [[ -f "$file" ]] || return 0
  local tmp="${file}.tmp.$$"
  sed '1s/^\xEF\xBB\xBF//' "$file" | tr -d '\r' > "$tmp"
  cat "$tmp" > "$file"
  rm -f "$tmp"
}

normalize_text_file "$ROOT_DIR/.env"
normalize_text_file "$DEPLOY_DIR/runtime.env"
normalize_text_file "$DEPLOY_DIR/docker-compose.yml"
normalize_text_file "$DEPLOY_DIR/platform.sh"
normalize_text_file "$DEPLOY_DIR/bundles/SHA256SUMS"

BUNDLE="$DEPLOY_DIR/bundles/lm-platform-images-x86_64.tar"
[[ -f "$BUNDLE" ]] || { echo "Missing $BUNDLE" >&2; exit 1; }
(cd "$DEPLOY_DIR/bundles" && tr -d '\r' < SHA256SUMS > SHA256SUMS.linux && sha256sum -c SHA256SUMS.linux)
docker load -i "$BUNDLE"
bash "$DEPLOY_DIR/platform.sh" up-offline
