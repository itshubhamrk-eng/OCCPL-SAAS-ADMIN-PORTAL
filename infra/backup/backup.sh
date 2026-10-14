#!/usr/bin/env bash
# Full logical backup of the platform databases (DEPLOYMENT.md §5).
#
# Consistent without locking the application: InnoDB snapshot (--single-transaction). The binary
# log position of the snapshot is written into the dump header (--source-data=2) so restore.sh can
# replay archived binary logs from exactly that point (point-in-time recovery).
#
# Usage: MYSQL_HOST=... MYSQL_USER=occpl_backup MYSQL_PASSWORD=... infra/backup/backup.sh /backups
# Output: <dir>/occpl-<UTC timestamp>.sql.gz and a .sha256 file next to it.
set -euo pipefail

target_dir=${1:?usage: backup.sh <target-dir>}
host=${MYSQL_HOST:-127.0.0.1}
port=${MYSQL_PORT:-3306}
user=${MYSQL_USER:-occpl_backup}
databases=${BACKUP_DATABASES:-occpl_core occpl_sample}
image=${MYSQL_CLIENT_IMAGE:-mysql:9.7}
: "${MYSQL_PASSWORD:?set MYSQL_PASSWORD}"

mkdir -p "$target_dir"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
file="$target_dir/occpl-$stamp.sql.gz"
started=$(date +%s)

# The client runs in the MySQL image so its version always matches the server.
# shellcheck disable=SC2086
docker run --rm --network host -e MYSQL_PWD="$MYSQL_PASSWORD" "$image" \
  mysqldump --host="$host" --port="$port" --user="$user" \
    --single-transaction --source-data=2 --quick --routines --triggers --events \
    --hex-blob --default-character-set=utf8mb4 --set-gtid-purged=AUTO \
    --databases $databases \
  | gzip -6 > "$file.partial"
mv "$file.partial" "$file"
(cd "$target_dir" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256")

echo "backup: $file ($(du -h "$file" | cut -f1), $(( $(date +%s) - started )) s)"
zcat "$file" | head -40 | grep -m1 "CHANGE REPLICATION SOURCE TO" || true
