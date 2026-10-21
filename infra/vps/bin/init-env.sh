#!/usr/bin/env bash
# Writes /opt/occpl/.env (mode 600) with generated passwords and the answers to a few questions.
# Refuses to overwrite an existing file (the generated passwords are baked into the database).
set -euo pipefail
cd "$(dirname "$0")/.."
[ ! -e .env ] || { echo ".env exists; edit it by hand (passwords already in the database cannot be regenerated)" >&2; exit 1; }

ask() { # var prompt default [secret]
  local v d=${3:-}
  if [ "${4:-}" = secret ]; then read -r -s -p "$2: " v; echo; else read -r -p "$2 [$d]: " v; fi
  printf -v "$1" '%s' "${v:-$d}"
}
rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 40; }

ask DOMAIN "Domain (e.g. occplsoftech.com, or dev.occplsoftech.com for the test server)" occplsoftech.com
mail_domain=$(awk -F. '{print $(NF-1) "." $NF}' <<< "$DOMAIN") # mailboxes live on the main domain, not on dev.
ask ACME_EMAIL "Email for certificate notices" "tech@$mail_domain"
ask ADMIN_IPS "IP addresses allowed to open the Keycloak console (space-separated, with /32)" ""
[ -n "$ADMIN_IPS" ] || { echo "ADMIN_IPS is required" >&2; exit 1; }
ask IMAGE_PREFIX "Image prefix" ghcr.io/occplsoftech/occpl
ask SMTP_HOST "SMTP host" smtp.hostinger.com
ask SMTP_PORT "SMTP port (465 = SSL, 587 = STARTTLS)" 465
ask SMTP_USERNAME "SMTP user" "tech@$mail_domain"
ask SMTP_PASSWORD "SMTP password" "" secret
ask SMTP_FROM_ADDRESS "Sender address" "$SMTP_USERNAME"
ask MAIL_NAME "Sender display name" "OCCPL Innovations"
ask BOOTSTRAP_SUPER_ADMIN_EMAIL "Email of the first Super Admin" ""
ask PROVISIONING_ALERT_RECIPIENTS "Email for provisioning alerts" "tech@$mail_domain"
if [ "$SMTP_PORT" = 465 ]; then ssl=true; starttls=false; else ssl=false; starttls=true; fi

umask 077
cat > .env <<ENV
DOMAIN=$DOMAIN
ACME_EMAIL=$ACME_EMAIL
ADMIN_IPS=$ADMIN_IPS
IMAGE_PREFIX=$IMAGE_PREFIX
RELEASE=none
MYSQL_ROOT_PASSWORD=$(rand)
DB_MIGRATOR_PASSWORD=$(rand)
DB_APP_PASSWORD=$(rand)
DB_BACKUP_PASSWORD=$(rand)
DB_KEYCLOAK_PASSWORD=$(rand)
REDIS_PASSWORD=$(rand)
KEYCLOAK_ADMIN_PASSWORD=$(rand)
KEYCLOAK_ADMIN_CLIENT_SECRET=$(rand)
PROVISIONING_CLIENT_SECRET=$(rand)
SMTP_HOST=$SMTP_HOST
SMTP_PORT=$SMTP_PORT
SMTP_USERNAME=$SMTP_USERNAME
SMTP_PASSWORD=$SMTP_PASSWORD
SMTP_SSL=$ssl
SMTP_STARTTLS=$starttls
SMTP_FROM_ADDRESS=$SMTP_FROM_ADDRESS
MAIL_FROM=$MAIL_NAME <$SMTP_FROM_ADDRESS>
BOOTSTRAP_SUPER_ADMIN_EMAIL=$BOOTSTRAP_SUPER_ADMIN_EMAIL
PROVISIONING_ALERT_RECIPIENTS=$PROVISIONING_ALERT_RECIPIENTS
RAZORPAY_KEY_ID=
RAZORPAY_KEY_SECRET=
RAZORPAY_WEBHOOK_SECRET=
ENV
echo "wrote $PWD/.env (mode 600). Back up this file somewhere safe: it holds every password."
