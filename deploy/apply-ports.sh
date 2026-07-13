#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
COMPOSE=(docker compose --env-file "$ROOT_DIR/.env")
if [[ -f "$DEPLOY_DIR/runtime.env" ]]; then
  COMPOSE+=(--env-file "$DEPLOY_DIR/runtime.env")
fi
COMPOSE+=(-f "$DEPLOY_DIR/docker-compose.yml")

read_setting() {
  local key="$1"
  "${COMPOSE[@]}" exec -T postgres sh -c \
    "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -Atc \"SELECT setting_value FROM system_setting WHERE setting_key='$key'\""
}

domain="$(read_setting deployment_domain)"
http_port="$(read_setting deployment_http_port)"
https_port="$(read_setting deployment_https_port)"
api_port="${API_PORT:-8510}"

[[ "$domain" =~ ^[a-z0-9.-]+$ ]] || { echo "Invalid domain from database" >&2; exit 1; }
[[ "$http_port" =~ ^[0-9]+$ && "$http_port" -ge 1 && "$http_port" -le 65535 ]] || { echo "Invalid HTTP port" >&2; exit 1; }
[[ "$https_port" =~ ^[0-9]+$ && "$https_port" -ge 1 && "$https_port" -le 65535 ]] || { echo "Invalid HTTPS port" >&2; exit 1; }
[[ "$http_port" != "$https_port" ]] || { echo "HTTP and HTTPS ports must differ" >&2; exit 1; }
[[ "$api_port" =~ ^[0-9]+$ && "$api_port" -ge 1 && "$api_port" -le 65535 ]] || { echo "Invalid API port" >&2; exit 1; }
[[ "$api_port" != "$http_port" && "$api_port" != "$https_port" ]] || { echo "API port must differ from HTTP and HTTPS ports" >&2; exit 1; }

public_base="https://$domain"
if [[ "$https_port" != "443" ]]; then
  public_base="$public_base:$https_port"
fi

cat > "$DEPLOY_DIR/runtime.env" <<EOF
APP_DOMAIN=$domain
HTTP_PORT=$http_port
HTTPS_PORT=$https_port
API_PORT=$api_port
PUBLIC_BASE_URL=$public_base
PUBLIC_WEB_BASE_URL=$public_base
EOF

COMPOSE=(docker compose --env-file "$ROOT_DIR/.env" --env-file "$DEPLOY_DIR/runtime.env" -f "$DEPLOY_DIR/docker-compose.yml")
"${COMPOSE[@]}" up -d --no-deps --force-recreate backend gateway
echo "Applied HTTP $http_port, HTTPS $https_port, and API $api_port for $domain"
echo "Ensure the OAuth2 provider allows callback: $public_base/api/auth/oauth2/callback"
