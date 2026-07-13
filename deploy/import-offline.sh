#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE="$DEPLOY_DIR/bundles/lm-platform-images-x86_64.tar"
[[ -f "$BUNDLE" ]] || { echo "Missing $BUNDLE" >&2; exit 1; }
(cd "$DEPLOY_DIR/bundles" && tr -d '\r' < SHA256SUMS > SHA256SUMS.linux && sha256sum -c SHA256SUMS.linux)
docker load -i "$BUNDLE"
bash "$DEPLOY_DIR/platform.sh" up-offline
