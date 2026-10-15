#!/bin/bash
# Creates the Keycloak database and the least-privilege accounts (DATABASE.md §6): the migrator owns
# DDL, the application is DML-only, the backup account reads. Runs only when the volume is created.
set -euo pipefail
mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS keycloak CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'occpl_migrator'@'%' IDENTIFIED BY '${DB_MIGRATOR_PASSWORD}';
CREATE USER IF NOT EXISTS 'occpl_app'@'%' IDENTIFIED BY '${DB_APP_PASSWORD}';
CREATE USER IF NOT EXISTS 'occpl_backup'@'%' IDENTIFIED BY '${DB_BACKUP_PASSWORD}';
CREATE USER IF NOT EXISTS 'keycloak'@'%' IDENTIFIED BY '${DB_KEYCLOAK_PASSWORD}';
GRANT ALL PRIVILEGES ON occpl_core.* TO 'occpl_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON occpl_core.* TO 'occpl_app'@'%';
GRANT ALL PRIVILEGES ON keycloak.* TO 'keycloak'@'%';
-- Backups (infra/backup): consistent dumps and binary-log streaming, no write access.
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, LOCK TABLES ON occpl_core.* TO 'occpl_backup'@'%';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, LOCK TABLES ON keycloak.* TO 'occpl_backup'@'%';
GRANT RELOAD, PROCESS, REPLICATION CLIENT, REPLICATION SLAVE, BACKUP_ADMIN ON *.* TO 'occpl_backup'@'%';
GRANT SELECT ON mysql.role_edges TO 'occpl_backup'@'%';
GRANT SELECT ON mysql.default_roles TO 'occpl_backup'@'%';
FLUSH PRIVILEGES;
SQL
