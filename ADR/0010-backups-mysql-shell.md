# ADR-0010: Backups with MySQL Shell dumps and binary-log increments

- Status: Accepted · Date: 2026-10-14

## Context
H-4 required a backup and restore procedure with measured RPO/RTO (targets 15 min / 4 h). The
original plan named Percona XtraBackup or managed snapshots plus binlog shipping. The databases run
in the `mysql:9.7` image, which ships MySQL Shell but no `mysqlbinlog`; XtraBackup releases lag new
MySQL versions.

## Decision
- **Full backup**: MySQL Shell `util.dumpInstance` limited to the platform schemas — consistent
  (`BACKUP_ADMIN` lock instead of a global read lock), parallel, zstd-compressed, nightly.
- **Increments**: `util.dumpBinlogs` every 5 minutes, appended to `<full>-binlog/`; the first increment
  starts at the binary-log position recorded by the full backup.
- **Restore**: `util.loadDump` into an empty server, then `util.loadBinlogs` (GTID-aware), with
  `stopBefore <GTID>` for point-in-time recovery before a bad transaction.
- Tools run in the server image (client = server version); a least-privilege `occpl_backup` account;
  passwords on stdin.

## Consequences
+ Measured on 1 M users: full backup 5 s / 39 MB, increments 1–2 s, restore ~42 s; RPO ≤ 5 min.
+ No extra product to install or license; works the same on a VM or a managed MySQL with binlog access.
− Logical dumps grow linearly with data; at several hundred GB, re-evaluate physical backups
  (MySQL Clone plugin, XtraBackup) or provider snapshots for the full backup — increments stay.
− User accounts are not in the backup; they are recreated from the deployment tooling.
