#!/usr/bin/env bash
# One-time database setup on RDS (run on one instance via SSM before the first deploy, and again
# after restoring into a new instance): schemas and least-privilege accounts, TLS required.
# Idempotent; uses the RDS-managed master secret. Passwords come from the environment's app secret.
set -euo pipefail
export PATH="$PATH:/snap/bin" # aws-cli snap; SSM and cloud-init shells may lack it
# shellcheck disable=SC1091
source /etc/occpl/instance.env
config=$(aws ssm get-parameter --name "$SSM_PREFIX/config" --query Parameter.Value --output text)
cfg() { jq -r --arg k "$1" '.[$k] // ""' <<< "$config"; }
secret() { aws secretsmanager get-secret-value --secret-id "$1" --query SecretString --output text; }
master=$(secret "$(cfg db_master_secret_arn)")
app=$(secret "$(cfg app_secret_arn)")
pw() { jq -r --arg k "$1" '.[$k]' <<< "$app"; }

sql=$(cat <<SQL
CREATE DATABASE IF NOT EXISTS occpl_core CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS keycloak CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'occpl_migrator'@'%' IDENTIFIED BY '$(pw db_migrator_password)' REQUIRE SSL;
CREATE USER IF NOT EXISTS 'occpl_app'@'%' IDENTIFIED BY '$(pw db_app_password)' REQUIRE SSL;
CREATE USER IF NOT EXISTS 'occpl_backup'@'%' IDENTIFIED BY '$(pw db_backup_password)' REQUIRE SSL;
CREATE USER IF NOT EXISTS 'keycloak'@'%' IDENTIFIED BY '$(pw db_keycloak_password)' REQUIRE SSL;
ALTER USER 'occpl_migrator'@'%' IDENTIFIED BY '$(pw db_migrator_password)';
ALTER USER 'occpl_app'@'%' IDENTIFIED BY '$(pw db_app_password)';
ALTER USER 'occpl_backup'@'%' IDENTIFIED BY '$(pw db_backup_password)';
ALTER USER 'keycloak'@'%' IDENTIFIED BY '$(pw db_keycloak_password)';
GRANT ALL PRIVILEGES ON occpl_core.* TO 'occpl_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON occpl_core.* TO 'occpl_app'@'%';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, LOCK TABLES ON occpl_core.* TO 'occpl_backup'@'%';
GRANT ALL PRIVILEGES ON keycloak.* TO 'keycloak'@'%';
SQL
)
# The client runs in the MySQL 8.4 image (matching the server); credentials via the environment only.
docker run --rm -i -e MYSQL_PWD="$(jq -r .password <<< "$master")" mysql:8.4 \
  mysql --host="$(cfg db_host)" --port="$(cfg db_port)" --user="$(jq -r .username <<< "$master")" \
  --ssl-mode=REQUIRED <<< "$sql"
echo "databases and accounts ready on $(cfg db_host)"
