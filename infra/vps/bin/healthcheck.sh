#!/usr/bin/env bash
# Every 5 minutes (cron): checks that the services answer and the disk is not filling up, then pings
# HEARTBEAT_URL (e.g. a free healthchecks.io check, which emails you when the pings stop).
# Silent unless something is wrong; problems go to /var/log/occpl-health.log.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
HEARTBEAT_URL=$(sed -n "s/^HEARTBEAT_URL=//p" .env | tail -1)
problems=()
curl -fs -o /dev/null -m 5 http://127.0.0.1:9000/health/ready || problems+=("keycloak not ready")
curl -fs -o /dev/null -m 5 http://127.0.0.1:8081/actuator/health/readiness || problems+=("core-platform not ready")
curl -fs -o /dev/null -m 5 http://127.0.0.1:8088/healthz || problems+=("admin-portal not healthy")
used=$(df --output=pcent / | tail -1 | tr -dc 0-9)
[ "$used" -lt 85 ] || problems+=("disk ${used}% full")
if [ ${#problems[@]} -gt 0 ]; then
  echo "$(date -u +%FT%TZ) ${problems[*]}" >> /var/log/occpl-health.log 2>/dev/null || true
  exit 1
fi
[ -z "$HEARTBEAT_URL" ] || curl -fsS -m 10 -o /dev/null "$HEARTBEAT_URL" || true
