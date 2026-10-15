# Backup and restore drill (BACKLOG H-4)

Performed on 2026-10-14 against the load-test data set (10,000 companies, 1,000,001 users,
1,049,996 memberships; `occpl_core` 1.08 GB on disk) in the local Docker stack, with the
application writing (k6, ~105 requests/s) between the backups. Same 4-vCPU VM as the load test, so
the timings are a baseline for this size, not a production figure. All numbers below were measured.

## Procedure (scripts in this directory, MySQL Shell 9.7 from the server image)

| Step | Command | Result |
|---|---|---|
| Full backup | `backup.sh /backups` (util.dumpInstance, platform schemas, consistent, 4 threads) | 6.2 M rows, 595 MB → **39 MB** (zstd), **4–5 s**, no application downtime |
| Writes A, B | k6 for 70 s | +702 usage records, +211 audit entries |
| Operator mistake | `DELETE FROM company_contact WHERE type = 'TECHNICAL'` (GTID `a400…:29407`) | 197 → 0 contacts |
| Writes C, D | k6 for 40 s | contacts re-created by the test: 92 |
| Incremental backup 1 | `binlog-backup.sh /backups` (util.dumpBinlogs from the full backup's position) | 284 KB, **2 s** |
| Writes E | k6 for 15 s | |
| Incremental backup 2 | `binlog-backup.sh /backups` (appends to `<full>-binlog/`) | +76 KB, **1 s** |

## Restore 1 — point in time, just before the mistake

New, empty MySQL 9.7 server; `STOP_BEFORE=a4002709-…:29407 restore.sh /backups`.

| Phase | Time |
|---|---|
| Server start (container) | 8 s |
| Full backup loaded (`util.loadDump`, 4 threads) | 26 s |
| Increments applied up to the stop point (`util.loadBinlogs`) | 5 s |
| **Total, empty server → restored data** | **41 s** |

The restored database matched the state recorded right before the DELETE exactly: 197 contacts,
19,824 usage records, 7,858 audit entries, usage total 19,824, GTID set `…:1-29406`.

## Restore 2 — latest point

New, empty server; `restore.sh /backups`: **43 s** (load 25 s, increments 8 s). `CHECKSUM TABLE`
of 12 tables (companies, users, identities, memberships, roles, subscriptions, entitlements, usage
counters and records, contacts, audit log, Flyway history) identical to the live database. Then the
application accounts were recreated (`infra/docker/mysql/01-create-users.sh`) and Core started on the
restored server: Flyway validated all 17 migrations, readiness up and an authenticated
`GET /api/v1/me` answered 200 after **14 s**.

## Recovery objectives

| Objective | Target (PROJECT.md §2) | Measured / designed |
|---|---|---|
| RPO | 15 min | Increments every **5 min** (1–2 s each); the drill lost no transaction up to the last increment. RPO = interval + off-host copy delay. |
| RTO | 4 h | **≈ 1 min** for data + application start at this size (41–43 s restore + 14 s), plus provisioning a server and switching traffic, which depends on Phase 10 infrastructure. |

## Findings and changes made during the drill

- The MySQL 9.7 image has no `mysqlbinlog`; streaming binlogs with it was replaced by MySQL Shell's
  `dumpBinlogs`/`loadBinlogs` (GTID-aware, compressed, `stopBefore` for point-in-time recovery).
- `dumpBinlogs` cannot continue from a schema-filtered dump, so the first increment starts at the
  binary-log position recorded in the full backup; later increments append automatically.
- Least privilege for `occpl_backup`: SELECT/SHOW VIEW/TRIGGER/EVENT/LOCK TABLES on the platform
  schemas, RELOAD, PROCESS, REPLICATION CLIENT/SLAVE, BACKUP_ADMIN, and SELECT on only
  `mysql.role_edges` and `mysql.default_roles` (MySQL Shell checks roles) — not `mysql.user`.
- Passwords reach MySQL Shell on stdin, never as command-line arguments.
- User accounts are not part of the backup: recreate them with the init script (or the Phase 10
  secrets tooling) before starting the services.

## Not covered yet (Phase 10)

- Copying backups off the host (object storage with versioning and retention) and restoring from there.
- The production Keycloak database (local Keycloak runs in development mode with its own store).
- Encryption of backup files at rest; scheduled timers and alerts when a backup fails or is late.
- A drill on production-sized hardware and data volume.
