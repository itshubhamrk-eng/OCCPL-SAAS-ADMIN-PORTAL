#!/usr/bin/env bash
# One-time (safe to repeat) setup of the Courier Management product on this server:
#   - adds its settings and generated secrets to .env (existing values are kept)
#   - creates its MySQL database and user
# Afterwards: docker compose pull && docker compose up -d   (images come from the courier-service and courier-web repositories' Image workflow)
#   bin/courier-setup.sh <courier-host> <default-plan-code>      e.g. courier.dev.occplsoftech.com STARTER
set -euo pipefail
cd "$(dirname "$0")/.."
host=${1:?usage: courier-setup.sh <courier-host> <default-plan-code>}
plan=${2:?usage: courier-setup.sh <courier-host> <default-plan-code>}
[ -f .env ] || { echo "no .env: run bin/init-env.sh first" >&2; exit 1; }

put() { # key value: keep an existing value
  grep -q "^$1=" .env || printf '%s=%s\n' "$1" "$2" >> .env
}
put COURIER_HOST "$host"
put COURIER_DEFAULT_PLAN_CODE "$plan"
put COURIER_DB_PASSWORD "$(openssl rand -hex 24)"
put COURIER_JWT_SECRET "$(openssl rand -base64 48 | tr -d '\n')"
put COURIER_SECRETS_ENCRYPTION_KEY "$(openssl rand -base64 32 | tr -d '\n')"
if grep -q '^COMPOSE_PROFILES=' .env; then
  grep '^COMPOSE_PROFILES=' .env | grep -q courier || sed -i 's/^COMPOSE_PROFILES=\(.*\)$/COMPOSE_PROFILES=\1,courier/' .env
else
  echo 'COMPOSE_PROFILES=courier' >> .env
fi
chmod 600 .env

set -a; . ./.env; set +a
docker compose up -d --wait mysql
docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS courier_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'courier'@'%' IDENTIFIED BY '${COURIER_DB_PASSWORD}';
ALTER USER 'courier'@'%' IDENTIFIED BY '${COURIER_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON courier_db.* TO 'courier'@'%';
SQL
echo "courier settings written to .env and database ready; now: docker compose pull && docker compose up -d"
