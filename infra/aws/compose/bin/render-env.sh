#!/usr/bin/env bash
# Writes /etc/occpl/{core,keycloak,portal}.env (root only, mode 600) from the environment's SSM
# configuration and Secrets Manager secrets. Run by deploy.sh; never prints secret values.
set -euo pipefail
export PATH="$PATH:/snap/bin" # aws-cli snap; SSM and cloud-init shells may lack it
# shellcheck disable=SC1091
source /etc/occpl/instance.env
out=${OCCPL_ENV_DIR:-/etc/occpl}

config=$(aws ssm get-parameter --name "$SSM_PREFIX/config" --query Parameter.Value --output text)
cfg() { jq -r --arg k "$1" '.[$k] // ""' <<< "$config"; }
secret() { aws secretsmanager get-secret-value --secret-id "$1" --query SecretString --output text; }

app=$(secret "$(cfg app_secret_arn)")
smtp=$(secret "$(cfg ses_smtp_secret_arn)")
razorpay=$(secret "$(cfg razorpay_secret_arn)")
val() { jq -r --arg k "$2" '.[$k] // ""' <<< "$1"; }

admin="https://$(cfg admin_host)"
api="https://$(cfg api_host)"
auth="https://$(cfg auth_host)"
db="$(cfg db_host):$(cfg db_port)"
keycloak_internal="http://host.docker.internal:8180" # Keycloak on the host network

umask 077
mkdir -p "$out"
cat > "$out/core.env.tmp" <<ENV
DB_URL=jdbc:mysql://$db/occpl_core?sslMode=REQUIRED
DB_USERNAME=occpl_app
DB_PASSWORD=$(val "$app" db_app_password)
DB_MIGRATOR_USERNAME=occpl_migrator
DB_MIGRATOR_PASSWORD=$(val "$app" db_migrator_password)
OIDC_ISSUER_URI=$auth/realms/occpl
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI=$keycloak_internal/realms/occpl/protocol/openid-connect/certs
CORS_ALLOWED_ORIGINS=$admin$(extra=$(cfg cors_extra_origins); [ -n "$extra" ] && printf ',%s' "$extra")
API_DOCS_ENABLED=false
KEYCLOAK_BASE_URL=$keycloak_internal
KEYCLOAK_ADMIN_CLIENT_SECRET=$(val "$app" keycloak_admin_client_secret)
REDIS_URL=rediss://:$(val "$app" redis_auth_token)@$(cfg redis_host):6379
SMTP_HOST=$(val "$smtp" host)
SMTP_PORT=$(val "$smtp" port)
SMTP_USERNAME=$(val "$smtp" username)
SMTP_PASSWORD=$(val "$smtp" password)
SMTP_AUTH=true
SMTP_STARTTLS=true
MAIL_FROM=$(cfg mail_from)
INVITATION_ACCEPT_URL=$admin/invitations/accept
PROVISIONING_CLIENT_SECRET=$(val "$app" provisioning_client_secret)
PROVISIONING_TOKEN_URI=$keycloak_internal/realms/occpl/protocol/openid-connect/token
PROVISIONING_CALLBACK_BASE_URL=$api
PROVISIONING_ALERT_RECIPIENTS=$(cfg provisioning_alert_recipients)
RAZORPAY_KEY_ID=$(val "$razorpay" key_id)
RAZORPAY_KEY_SECRET=$(val "$razorpay" key_secret)
RAZORPAY_WEBHOOK_SECRET=$(val "$razorpay" webhook_secret)
BOOTSTRAP_SUPER_ADMIN_EMAIL=$(cfg bootstrap_super_admin_email)
ENV

cat > "$out/keycloak.env.tmp" <<ENV
KC_DB_URL=jdbc:mysql://$db/keycloak?sslMode=REQUIRED
KC_DB_USERNAME=keycloak
KC_DB_PASSWORD=$(val "$app" db_keycloak_password)
KC_HOSTNAME=$auth
KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true
KC_PROXY_HEADERS=xforwarded
KC_HTTP_ENABLED=true
KC_HTTP_PORT=8180
KC_HTTP_MANAGEMENT_PORT=9000
KC_LOG_CONSOLE_OUTPUT=json
KC_BOOTSTRAP_ADMIN_USERNAME=bootstrap-admin
KC_BOOTSTRAP_ADMIN_PASSWORD=$(val "$app" keycloak_admin_password)
JAVA_OPTS_KC_HEAP=-XX:MaxRAMPercentage=70
KC_PORTAL_URL=$admin
KC_SMTP_HOST=$(val "$smtp" host)
KC_SMTP_PORT=$(val "$smtp" port)
KC_SMTP_STARTTLS=true
KC_SMTP_SSL=false
KC_SMTP_USER=$(val "$smtp" username)
KC_SMTP_PASSWORD=$(val "$smtp" password)
KC_SMTP_FROM=no-reply@$(cfg admin_host | sed -E 's/^admin\.(staging\.)?//')
KEYCLOAK_ADMIN_CLIENT_SECRET=$(val "$app" keycloak_admin_client_secret)
PROVISIONING_CLIENT_SECRET=$(val "$app" provisioning_client_secret)
ENV

cat > "$out/portal.env.tmp" <<ENV
API_BASE_URL=$api/api/v1
OIDC_ISSUER=$auth/realms/occpl
OIDC_CLIENT_ID=occpl-admin-portal
CSP_CONNECT_SRC=$api $auth
ENV

for f in core keycloak portal; do mv "$out/$f.env.tmp" "$out/$f.env"; done
echo "rendered $out/{core,keycloak,portal}.env for $(cfg env)"
