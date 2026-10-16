#!/usr/bin/env python3
"""Generates the production realm import from the development realm (infra/docker/keycloak).

One source of truth for flows, policies, roles and mappers; production differs only in:
- portal client: https://${KC_PORTAL_URL} redirect/web origins, no Swagger UI redirect;
- no sample-product clients or client scope (each product is registered on its own);
- SES SMTP through placeholders, HTTPS required for every request;
- the OCCPL sign-in theme (themes/occpl).
Keycloak substitutes ${VAR} from the environment at import time (first start only).

Usage: python3 make-production-realm.py [--check]   (--check fails if the committed file is stale)
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parents[2] / "docker" / "keycloak" / "occpl-realm.json"
TARGET = HERE / "occpl-realm.production.json"
SAMPLE_CLIENTS = {"product-sample-service", "product-sample-web"}
SAMPLE_SCOPES = {"product-sample"}


def production(realm: dict) -> dict:
    realm = json.loads(json.dumps(realm))  # deep copy
    realm["sslRequired"] = "all"
    realm["loginTheme"] = "occpl"  # themes/occpl, copied into the image (Dockerfile)
    realm["smtpServer"] = {
        "host": "${KC_SMTP_HOST}", "port": "${KC_SMTP_PORT}", "starttls": "${KC_SMTP_STARTTLS}", "ssl": "${KC_SMTP_SSL}",
        "auth": "true", "user": "${KC_SMTP_USER}", "password": "${KC_SMTP_PASSWORD}",
        "from": "${KC_SMTP_FROM}", "fromDisplayName": "OCCPL Innovations Sign-in",
    }
    realm["clients"] = [c for c in realm["clients"] if c["clientId"] not in SAMPLE_CLIENTS]
    for client in realm["clients"]:
        if client["clientId"] == "occpl-admin-portal":
            client["redirectUris"] = ["${KC_PORTAL_URL}/*"]
            client["webOrigins"] = ["${KC_PORTAL_URL}"]
            client.setdefault("attributes", {})["post.logout.redirect.uris"] = "${KC_PORTAL_URL}/*"
        if "optionalClientScopes" in client:
            client["optionalClientScopes"] = [s for s in client["optionalClientScopes"] if s not in SAMPLE_SCOPES]
    realm["clientScopes"] = [s for s in realm.get("clientScopes", []) if s["name"] not in SAMPLE_SCOPES]
    users = realm.get("users", [])
    realm["users"] = [u for u in users if u.get("username", "").removeprefix("service-account-") not in SAMPLE_CLIENTS]
    return realm


def main() -> int:
    rendered = json.dumps(production(json.loads(SOURCE.read_text())), indent=2, ensure_ascii=False) + "\n"
    if "--check" in sys.argv:
        if TARGET.read_text() != rendered:
            print(f"{TARGET.name} is stale: run {Path(__file__).name}", file=sys.stderr)
            return 1
        return 0
    TARGET.write_text(rendered)
    return 0


if __name__ == "__main__":
    sys.exit(main())
