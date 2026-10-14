#!/usr/bin/env bash
# Restores a full backup into an EMPTY MySQL server and, optionally, replays archived binary logs
# up to a point in time (DEPLOYMENT.md §5). Never point this at a server holding live data: it
# recreates the backed-up databases.
#
# Usage: MYSQL_HOST=... MYSQL_USER=root MYSQL_PASSWORD=... \
#        infra/backup/restore.sh <backup.sql.gz> [<binlog-dir> [<stop-datetime UTC, e.g. "2026-10-14 06:30:00">]]
# Without a stop time all archived events are replayed (recover to the latest point).
set -euo pipefail

backup=${1:?usage: restore.sh <backup.sql.gz> [binlog-dir [stop-datetime]]}
binlog_dir=${2:-}
stop=${3:-}
host=${MYSQL_HOST:-127.0.0.1}
port=${MYSQL_PORT:-3306}
user=${MYSQL_USER:-root}
image=${MYSQL_CLIENT_IMAGE:-mysql:9.7}
: "${MYSQL_PASSWORD:?set MYSQL_PASSWORD}"

if [ -f "$backup.sha256" ]; then
  (cd "$(dirname "$backup")" && sha256sum -c "$(basename "$backup").sha256")
fi
mysql_cli() {
  docker run --rm -i --network host -e MYSQL_PWD="$MYSQL_PASSWORD" "$image" \
    mysql --host="$host" --port="$port" --user="$user" --default-character-set=utf8mb4 "$@"
}

started=$(date +%s)
zcat "$backup" | mysql_cli
echo "full backup loaded in $(( $(date +%s) - started )) s"

if [ -n "$binlog_dir" ]; then
  position=$(zcat "$backup" | head -60 | grep -m1 "CHANGE REPLICATION SOURCE TO" \
    | sed -E "s/.*SOURCE_LOG_FILE='([^']+)', SOURCE_LOG_POS=([0-9]+).*/\1 \2/")
  read -r first_file first_pos <<< "$position"
  [ -n "$first_file" ] || { echo "no binary log position in the backup header" >&2; exit 1; }
  files=$(ls "$binlog_dir" | grep -E '\.[0-9]{6}$' | sort | awk -v f="$first_file" '$0 >= f')
  echo "replaying binary logs from $first_file:$first_pos${stop:+ until $stop}: $(echo "$files" | tr '\n' ' ')"
  args=()
  [ -n "$stop" ] && args+=(--stop-datetime="$stop")
  replay_started=$(date +%s)
  # The first file starts at the snapshot position; the following ones from their beginning.
  # shellcheck disable=SC2086
  docker run --rm --network host -v "$(realpath "$binlog_dir")":/archive:ro "$image" sh -c \
    "cd /archive && mysqlbinlog --start-position=$first_pos ${args[*]:-} $first_file && \
     for f in $(echo "$files" | tail -n +2 | tr '\n' ' '); do mysqlbinlog ${args[*]:-} \$f; done" \
    | mysql_cli
  echo "binary logs replayed in $(( $(date +%s) - replay_started )) s"
fi
echo "restore finished in $(( $(date +%s) - started )) s"
