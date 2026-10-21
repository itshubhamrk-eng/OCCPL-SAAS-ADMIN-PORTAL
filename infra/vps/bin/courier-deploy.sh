#!/usr/bin/env bash
# Deploys ONE courier image on this server and waits until it is healthy; if it does not become healthy the
# previous image is started again and the exit code is 1. Called by the release workflows of the courier-service and
# courier-web repositories over SSH, and usable by hand:
#   bin/courier-deploy.sh backend|frontend|portal <release>     release = full git SHA of the image tag, or "dev"
# As an SSH forced command (authorized_keys: command="/opt/occpl/bin/courier-deploy.sh",restrict ssh-ed25519 ...) the
# arguments come from the client's command line, so that key can do nothing else on this server.
set -euo pipefail
cd "$(dirname "$0")/.."
exec > >(tee -a /var/log/occpl-deploy.log 2>/dev/null || cat) 2>&1

if [ -n "${SSH_ORIGINAL_COMMAND:-}" ]; then
  read -r -a original <<<"$SSH_ORIGINAL_COMMAND"
  set -- "${original[@]}"
fi
usage() { echo "usage: courier-deploy.sh backend|frontend|portal <40-character git SHA | dev>" >&2; exit 2; }
[ $# -eq 2 ] || usage
part=$1
release=$2
[[ "$release" =~ ^[0-9a-f]{40}$ || "$release" == dev ]] || usage
case "$part" in
  backend) service=courier-backend; var=COURIER_BACKEND_RELEASE ;;
  frontend) service=courier-frontend; var=COURIER_FRONTEND_RELEASE ;;
  portal) service=courier-portal; var=COURIER_PORTAL_RELEASE ;;
  *) usage ;;
esac
[ -f .env ] || { echo "no .env: run bin/init-env.sh first" >&2; exit 1; }
grep -q '^COMPOSE_PROFILES=.*courier' .env || { echo "courier is not enabled here: run bin/courier-setup.sh first" >&2; exit 1; }
log() { echo "$(date -u +%FT%TZ) $*"; }

current() { sed -n "s/^$var=//p" .env | tail -1; }
set_release() {
  if grep -q "^$var=" .env; then sed -i "s/^$var=.*/$var=$1/" .env; else printf '%s=%s\n' "$var" "$1" >> .env; fi
}
previous=$(current)
previous=${previous:-dev}

wait_healthy() { # seconds
  local deadline=$((SECONDS + $1)) id status
  while [ $SECONDS -lt $deadline ]; do
    id=$(docker compose ps -q "$service")
    status=$([ -n "$id" ] && docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" || echo missing)
    if [ "$status" = healthy ] || [ "$status" = running ]; then echo "$service $status"; return 0; fi
    sleep 5
  done
  echo "$service not healthy after $1 s"; docker compose ps "$service" || true; docker compose logs --tail 60 "$service" || true
  return 1
}

start() { # release
  set_release "$1"
  # No errexit inside a function used as a condition: every step needs an explicit check.
  docker compose pull --quiet "$service" || return 1
  docker compose up -d --no-deps "$service" || return 1
  wait_healthy 240 || return 1
  if [ "$part" = backend ]; then
    # The web container's nginx looks the backend up once at start; after the backend was recreated it would keep
    # sending requests to the old address.
    docker compose restart courier-frontend || return 1
  fi
}

log "deploying courier $part $release (running: $previous)"
if start "$release"; then
  log "courier $part $release running"
  exit 0
fi
log "deploy of courier $part $release FAILED"
if [ "$previous" != "$release" ]; then
  log "rolling back to $previous"
  if start "$previous"; then log "back on $previous"; else log "ROLLBACK FAILED: investigate with docker compose logs"; fi
fi
exit 1
