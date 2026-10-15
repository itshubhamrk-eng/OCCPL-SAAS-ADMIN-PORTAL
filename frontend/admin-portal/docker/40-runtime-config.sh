#!/bin/sh
# Writes the Angular runtime configuration from environment variables at container start so that
# the same image is promoted unchanged through staging and production.
set -eu
: "${API_BASE_URL:?API_BASE_URL is required}"
: "${OIDC_ISSUER:?OIDC_ISSUER is required}"
: "${OIDC_CLIENT_ID:?OIDC_CLIENT_ID is required}"

json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

cat > /usr/share/nginx/html/config.json <<JSON
{
  "apiBaseUrl": "$(json_escape "$API_BASE_URL")",
  "oidc": {
    "issuer": "$(json_escape "$OIDC_ISSUER")",
    "clientId": "$(json_escape "$OIDC_CLIENT_ID")"
  }
}
JSON
