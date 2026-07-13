#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.env"
  set +a
fi
if [[ -f "$DEPLOY_DIR/runtime.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$DEPLOY_DIR/runtime.env"
  set +a
fi

if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose -f "$DEPLOY_DIR/docker-compose.yml")
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose -f "$DEPLOY_DIR/docker-compose.yml")
else
  echo "Docker Compose is required. Install docker compose plugin or docker-compose." >&2
  exit 1
fi

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
