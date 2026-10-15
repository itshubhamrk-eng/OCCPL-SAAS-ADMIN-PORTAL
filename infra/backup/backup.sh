#!/usr/bin/env bash
# Full backup of the platform databases with MySQL Shell (DEPLOYMENT.md §5): consistent, parallel,
# zstd-compressed dump (util.dumpInstance limited to the platform schemas, no user accounts). Its
# metadata records the binary-log position and GTID set, which binlog-backup.sh continues from.
#
# Usage: MYSQL_HOST=... MYSQL_USER=occpl_backup MYSQL_PASSWORD=... infra/backup/backup.sh /backups
# Creates /backups/<UTC timestamp>-full/. Run nightly; keep at least 7 days (and copies off the host).
set -euo pipefail

base=${1:?usage: backup.sh <backup-dir>}
host=${MYSQL_HOST:-127.0.0.1}
port=${MYSQL_PORT:-3306}
user=${MYSQL_USER:-occpl_backup}
schemas=${BACKUP_SCHEMAS:-occpl_core,occpl_sample}
image=${MYSQL_CLIENT_IMAGE:-mysql:9.7} # MySQL Shell 9.x: only it has util.dumpBinlogs; it also dumps and loads 8.4 servers
threads=${BACKUP_THREADS:-4}
: "${MYSQL_PASSWORD:?set MYSQL_PASSWORD}"  # passed to MySQL Shell on stdin, never as an argument

mkdir -p "$base"
name="$(date -u +%Y%m%dT%H%M%SZ)-full"
started=$(date +%s)
include=$(echo "$schemas" | sed 's/[^,][^,]*/"&"/g')
# MySQL Shell runs in the server image so its version always matches the server.
docker run --rm -i --network host -e LC_ALL=C \
  -v "$(realpath "$base")":/backups --user "$(id -u):$(id -g)" -e HOME=/tmp "$image" \
  mysqlsh --uri "$user@$host:$port" --password --passwords-from-stdin --js --execute \
  "util.dumpInstance('/backups/$name', {includeSchemas: [$include], users: false, threads: $threads, consistent: true, showProgress: false})" <<< "$MYSQL_PASSWORD"
echo "backup: $base/$name ($(du -sh "$base/$name" | cut -f1), $(( $(date +%s) - started )) s)"
