#!/bin/bash
# Creates least-privilege accounts (DATABASE.md §6): the migrator owns DDL, the application is DML-only.
# The sample product gets its own database and accounts; Core's accounts have no access to it and
# vice versa (products own their data). Runs only when the MySQL volume is first created.
set -euo pipefail
mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE USER IF NOT EXISTS 'occpl_migrator'@'%' IDENTIFIED BY '${DB_MIGRATOR_PASSWORD}';
CREATE USER IF NOT EXISTS 'occpl_app'@'%' IDENTIFIED BY '${DB_APP_PASSWORD}';
GRANT ALL PRIVILEGES ON occpl_core.* TO 'occpl_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON occpl_core.* TO 'occpl_app'@'%';

CREATE DATABASE IF NOT EXISTS occpl_sample CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'sample_migrator'@'%' IDENTIFIED BY '${SAMPLE_DB_MIGRATOR_PASSWORD}';
CREATE USER IF NOT EXISTS 'sample_app'@'%' IDENTIFIED BY '${SAMPLE_DB_APP_PASSWORD}';
GRANT ALL PRIVILEGES ON occpl_sample.* TO 'sample_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON occpl_sample.* TO 'sample_app'@'%';

-- Backups (infra/backup): consistent dumps and binary-log streaming, no write access.
CREATE USER IF NOT EXISTS 'occpl_backup'@'%' IDENTIFIED BY '${DB_BACKUP_PASSWORD}';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, LOCK TABLES ON occpl_core.* TO 'occpl_backup'@'%';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, LOCK TABLES ON occpl_sample.* TO 'occpl_backup'@'%';
GRANT RELOAD, PROCESS, REPLICATION CLIENT, REPLICATION SLAVE ON *.* TO 'occpl_backup'@'%';
FLUSH PRIVILEGES;
SQL
