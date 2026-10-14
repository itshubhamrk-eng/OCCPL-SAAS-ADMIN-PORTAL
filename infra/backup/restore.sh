#!/usr/bin/env bash
# Restores into an EMPTY MySQL server (DEPLOYMENT.md §5): the newest full backup in <backup-dir>
# (or the one given), then all increments in <full>-binlog/. With STOP_BEFORE=<GTID> the
# replay stops right before that transaction (e.g. an accidental DELETE found with mysqlbinlog or
# performance_schema); without it, everything archived is applied (latest point).
#
# Usage: MYSQL_HOST=... MYSQL_USER=root MYSQL_PASSWORD=... [STOP_BEFORE=uuid:n] \
#        infra/backup/restore.sh <backup-dir> [<full-backup-name>]
# Never run against a server with live data: it creates the backed-up schemas.
set -euo pipefail

base=${1:?usage: restore.sh <backup-dir> [full-backup-name]}
host=${MYSQL_HOST:-127.0.0.1}
port=${MYSQL_PORT:-3306}
user=${MYSQL_USER:-root}
image=${MYSQL_CLIENT_IMAGE:-mysql:9.7}
threads=${RESTORE_THREADS:-4}
: "${MYSQL_PASSWORD:?set MYSQL_PASSWORD}"

full=${2:-$(ls "$base" | grep -- '-full$' | sort | tail -1)}
[ -f "$base/$full/@.done.json" ] || { echo "no completed full backup '$full' in $base" >&2; exit 1; }

shell() {
  docker run --rm -i --network host -e LC_ALL=C -v "$(realpath "$base")":/backups:ro -e HOME=/tmp "$image" \
    mysqlsh --uri "$user@$host:$port" --password --passwords-from-stdin --js --execute "$1" <<< "$MYSQL_PASSWORD"
}

started=$(date +%s)
shell "session.runSql('SET GLOBAL local_infile = 1');
util.loadDump('/backups/$full', {threads: $threads, loadUsers: false, updateGtidSet: 'append', skipBinlog: true, progressFile: '/tmp/load-progress.json', showProgress: false})"
echo "full backup $full loaded in $(( $(date +%s) - started )) s"

stop_option=""
[ -n "${STOP_BEFORE:-}" ] && stop_option="stopBefore: '$STOP_BEFORE'"
if [ -d "$base/$full-binlog" ]; then
  step=$(date +%s)
  shell "util.loadBinlogs('/backups/$full-binlog', {showProgress: false${stop_option:+, $stop_option}})"
  echo "increments applied in $(( $(date +%s) - step )) s"
fi
shell "session.runSql('SET GLOBAL local_infile = 0')"
echo "restore finished in $(( $(date +%s) - started )) s"
