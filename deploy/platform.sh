#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
COMPOSE=(docker compose --env-file "$ROOT_DIR/.env")
if [[ -f "$DEPLOY_DIR/runtime.env" ]]; then
  COMPOSE+=(--env-file "$DEPLOY_DIR/runtime.env")
fi
COMPOSE+=(-f "$DEPLOY_DIR/docker-compose.yml")

require_files() {
  [[ -f "$ROOT_DIR/.env" ]] || { echo "Missing $ROOT_DIR/.env" >&2; exit 1; }
}

case "${1:-}" in
  up)
    require_files
    "${COMPOSE[@]}" up -d --build
    ;;
  up-offline)
    require_files
    "${COMPOSE[@]}" up -d --no-build --pull never
    ;;
  down)
    "${COMPOSE[@]}" down
    ;;
  restart)
    require_files
    "${COMPOSE[@]}" up -d --force-recreate
    ;;
  status)
    "${COMPOSE[@]}" ps
    ;;
  logs)
    if [[ -n "${2:-}" ]]; then
      "${COMPOSE[@]}" logs -f --tail=200 "$2"
    else
      "${COMPOSE[@]}" logs -f --tail=200
    fi
    ;;
  *)
    echo "Usage: $0 {up|up-offline|down|restart|status|logs [service]}" >&2
    exit 2
    ;;
esac
