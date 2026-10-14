#!/usr/bin/env bash
# Post-deploy smoke test through the public entry point (load balancer, TLS, WAF).
# Usage: smoke-test.sh <host-suffix>   ("" for production, "staging." for staging)
set -euo pipefail
suffix=${1-}
domain=${DOMAIN:-occplsoftech.com}
admin="https://admin.$suffix$domain"
api="https://api.$suffix$domain"
auth="https://auth.$suffix$domain"
fail() { echo "SMOKE TEST FAILED: $*" >&2; exit 1; }

curl -fsS --max-time 10 "$admin/healthz" | grep -q ok || fail "$admin/healthz"
config=$(curl -fsS --max-time 10 "$admin/config.json")
[ "$(jq -r .apiBaseUrl <<< "$config")" = "$api/api/v1" ] || fail "portal apiBaseUrl: $config"
issuer=$(curl -fsS --max-time 10 "$auth/realms/occpl/.well-known/openid-configuration" | jq -r .issuer)
[ "$issuer" = "$auth/realms/occpl" ] || fail "issuer $issuer"
status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$auth/admin/master/console/")
[ "$status" = 403 ] || fail "Keycloak admin console reachable from the internet ($status)"
headers=$(curl -s -D - -o /dev/null --max-time 10 "$api/api/v1/me")
grep -q '^HTTP/[0-9.]* 401' <<< "$headers" || fail "API without token: $(head -1 <<< "$headers")"
grep -qi '^strict-transport-security' <<< "$headers" || fail "API sends no HSTS (proxy headers not trusted?)"
status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://admin.$suffix$domain/")
[ "$status" = 301 ] || fail "HTTP is not redirected to HTTPS ($status)"
echo "smoke test passed: $admin $api $auth"
