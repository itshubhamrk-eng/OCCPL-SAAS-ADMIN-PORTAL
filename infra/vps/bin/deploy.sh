#!/usr/bin/env bash
# Starts a release on this server and waits until every service is ready; if it does not become
# ready, the previous release is started again and the exit code is 1.
#   bin/deploy.sh <release>      release = full git SHA whose images exist in the registry
# The same command rolls back: run it with an earlier SHA. Brief downtime (about a minute) while
# containers restart: there is only one server (ADR-0012).
set -euo pipefail
cd "$(dirname "$0")/.."
exec > >(tee -a /var/log/occpl-deploy.log 2>/dev/null || cat) 2>&1

release=${1:?usage: deploy.sh <release>}
[[ "$release" =~ ^[0-9a-f]{40}$ ]] || { echo "release must be a full git SHA" >&2; exit 1; }
[ -f .env ] || { echo "no .env: run bin/init-env.sh first" >&2; exit 1; }
log() { echo "$(date -u +%FT%TZ) $*"; }

current() { sed -n 's/^RELEASE=//p' .env | tail -1; }
set_release() { sed -i "s/^RELEASE=.*/RELEASE=$1/" .env; }
previous=$(current)

wait_ready() { # name url seconds
  local deadline=$((SECONDS + $3))
  until curl -fs -o /dev/null --max-time 5 "$2"; do
    if [ $SECONDS -ge $deadline ]; then
      echo "$1 not ready after $3 s"; docker compose ps; docker compose logs --tail 60 "$1" || true; return 1
    fi
    sleep 5
  done
  echo "$1 ready"
}
start() { # release
  set_release "$1"
  # No errexit inside a function used as a condition: every step needs an explicit check.
  docker compose pull --quiet || return 1
  docker compose up -d --remove-orphans || return 1
  wait_ready keycloak http://127.0.0.1:9000/health/ready 420 &&
    wait_ready core-platform http://127.0.0.1:8081/actuator/health/readiness 300 &&
    wait_ready admin-portal http://127.0.0.1:8088/healthz 60
}

log "deploying $release (running: $previous)"
if start "$release"; then
  log "release $release running"
  docker image prune -af --filter "until=168h" >/dev/null || true
  exit 0
fi
log "deploy of $release FAILED"
if [ "$previous" != none ] && [ "$previous" != "$release" ]; then
  log "rolling back to $previous"
  if start "$previous"; then log "back on $previous"; else log "ROLLBACK FAILED: investigate with docker compose logs"; fi
fi
exit 1
