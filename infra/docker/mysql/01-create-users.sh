#!/bin/bash
# Creates least-privilege accounts (DATABASE.md §6): the migrator owns DDL, the application is DML-only.
set -euo pipefail
mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE USER IF NOT EXISTS 'occpl_migrator'@'%' IDENTIFIED BY '${DB_MIGRATOR_PASSWORD}';
CREATE USER IF NOT EXISTS 'occpl_app'@'%' IDENTIFIED BY '${DB_APP_PASSWORD}';
GRANT ALL PRIVILEGES ON occpl_core.* TO 'occpl_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON occpl_core.* TO 'occpl_app'@'%';
FLUSH PRIVILEGES;
SQL
