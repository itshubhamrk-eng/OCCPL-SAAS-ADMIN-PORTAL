#!/usr/bin/env bash
# Incremental backup: appends the binary logs written since the last increment (or since the newest
# full backup) to <full-backup>-binlog/ with MySQL Shell util.dumpBinlogs. Run every 5 minutes
# (systemd timer or cron): the recovery point objective is this interval plus the time it takes to
# copy the directory off the host.
#
# Usage: MYSQL_HOST=... MYSQL_USER=occpl_backup MYSQL_PASSWORD=... infra/backup/binlog-backup.sh /backups
set -euo pipefail

base=${1:?usage: binlog-backup.sh <backup-dir>}
host=${MYSQL_HOST:-127.0.0.1}
port=${MYSQL_PORT:-3306}
user=${MYSQL_USER:-occpl_backup}
image=${MYSQL_CLIENT_IMAGE:-mysql:8.4}
: "${MYSQL_PASSWORD:?set MYSQL_PASSWORD}"  # passed to MySQL Shell on stdin, never as an argument

# Newest completed full backup (MySQL Shell writes @.done.json last).
full=$(for d in "$base"/*-full; do if [ -f "$d/@.done.json" ]; then basename "$d"; fi; done | sort | tail -1)
[ -n "$full" ] || { echo "no completed full backup in $base; run backup.sh first" >&2; exit 1; }
target="$full-binlog"
if [ -d "$base/$target" ]; then
  option="" # MySQL Shell continues where the previous increment in this directory ended
else
  # First increment: start at the binary-log position recorded by the full backup. (The full backup
  # covers only the platform schemas, so 'since' cannot point at it.)
  option="startFrom: '$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(f\"{d['binlogFile']}:{d['binlogPosition']}\")" "$base/$full/@.json")', "
fi
started=$(date +%s)
docker run --rm -i --network host -e LC_ALL=C -v "$(realpath "$base")":/backups --user "$(id -u):$(id -g)" -e HOME=/tmp "$image" \
  mysqlsh --uri "$user@$host:$port" --password --passwords-from-stdin --js --execute \
  "util.dumpBinlogs('/backups/$target', {${option}showProgress: false})" <<< "$MYSQL_PASSWORD"
echo "incremental backup: $base/$target ($(ls "$base/$target" | grep -c '^[0-9]') increments, $(du -sh "$base/$target" | cut -f1), $(( $(date +%s) - started )) s)"
