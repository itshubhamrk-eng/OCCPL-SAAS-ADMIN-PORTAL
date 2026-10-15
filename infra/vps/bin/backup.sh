#!/usr/bin/env bash
# Cron entry point for backups (infra/backup scripts, MySQL Shell):
#   backup.sh full     nightly consistent dump of occpl_core and keycloak, prunes old local copies
#   backup.sh binlog   every 5 minutes: binary-log increment since the last backup
# Local copies in /var/backups/occpl (7 days). With BACKUP_S3_URI set in .env, each run also copies
# to that S3 location (lifecycle rules there keep the history). BACKUP_PING_URL is pinged on success.
set -euo pipefail
mode=${1:?usage: backup.sh full|binlog}
cd "$(dirname "$0")/.." || exit 1
envval() { sed -n "s/^$1=//p" .env | tail -1; } # .env is not sourced: values may contain spaces
BACKUP_S3_URI=$(envval BACKUP_S3_URI); BACKUP_PING_URL=$(envval BACKUP_PING_URL)
dir=${BACKUP_DIR:-/var/backups/occpl}
MYSQL_PASSWORD=$(envval DB_BACKUP_PASSWORD)
export MYSQL_USER=occpl_backup MYSQL_PASSWORD BACKUP_SCHEMAS=occpl_core,keycloak
lock=/tmp/occpl-backup.lock
exec 9>"$lock"
flock -n 9 || { echo "another backup is running"; exit 0; }

case "$mode" in
  full)
    backup/backup.sh "$dir"
    find "$dir" -maxdepth 1 -type d -name '*-full' -mtime +7 -exec sh -c 'rm -rf "$1" "$1-binlog"' _ {} \;
    ;;
  binlog) # the first increment needs a completed full backup
    if ! ls "$dir"/*-full/@.done.json >/dev/null 2>&1; then echo "no full backup yet"; exit 0; fi
    backup/binlog-backup.sh "$dir"
    ;;
  *) echo "usage: backup.sh full|binlog" >&2; exit 1 ;;
esac
if [ -n "$BACKUP_S3_URI" ]; then
  aws s3 sync "$dir" "$BACKUP_S3_URI" --only-show-errors --no-progress
fi
[ "$mode" = full ] && [ -n "$BACKUP_PING_URL" ] && curl -fsS -m 10 -o /dev/null "$BACKUP_PING_URL" || true
