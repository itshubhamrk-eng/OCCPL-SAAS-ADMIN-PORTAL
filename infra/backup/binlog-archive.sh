#!/usr/bin/env bash
# Continuously copies the server's binary logs to <dir> as they are written (DEPLOYMENT.md §5).
# Together with the nightly full backup this gives point-in-time recovery; the recovery point is
# the last event received (seconds behind the primary while this runs). Ship <dir> off the host
# (object storage with versioning) on a short interval.
#
# Usage: MYSQL_HOST=... MYSQL_USER=occpl_backup MYSQL_PASSWORD=... infra/backup/binlog-archive.sh /backups/binlog
# Runs until stopped; restart it with the same directory and it continues from the newest file.
set -euo pipefail

target_dir=${1:?usage: binlog-archive.sh <target-dir>}
host=${MYSQL_HOST:-127.0.0.1}
port=${MYSQL_PORT:-3306}
user=${MYSQL_USER:-occpl_backup}
image=${MYSQL_CLIENT_IMAGE:-mysql:9.7}
: "${MYSQL_PASSWORD:?set MYSQL_PASSWORD}"
mkdir -p "$target_dir"

# Start with the newest archived file (re-fetched whole) or the oldest file still on the server.
start=$(ls "$target_dir" 2>/dev/null | grep -E '\.[0-9]{6}$' | sort | tail -1 || true)
if [ -z "$start" ]; then
  start=$(docker run --rm --network host -e MYSQL_PWD="$MYSQL_PASSWORD" "$image" \
    mysql --host="$host" --port="$port" --user="$user" -N -B -e "SHOW BINARY LOGS" | head -1 | cut -f1)
fi
echo "archiving binary logs from $start into $target_dir"
exec docker run --rm --network host -e MYSQL_PWD="$MYSQL_PASSWORD" -v "$(realpath "$target_dir")":/archive \
  --user "$(id -u):$(id -g)" "$image" \
  mysqlbinlog --read-from-remote-server --host="$host" --port="$port" --user="$user" \
    --raw --stop-never --stop-never-slave-server-id=$((RANDOM + 10000)) --result-file=/archive/ "$start"
