#!/usr/bin/env python3
"""Prepares the local Docker stack for the k6 load test (BACKLOG H-3). Test environments only.

1. Keycloak: two load-test clients with the password grant (members; staff with password + TOTP),
   tokens valid for one hour and the same audience/amr mappers as the portal. The production realm
   file is not changed: this runs against a disposable stack.
2. A platform staff user with TOTP (Super Admin) creates the load-test product and plan through
   Core's API, like an operator would.
3. seed/seed_sql.py seeds companies, users, memberships, subscriptions and entitlements.
4. A sample of seeded users (company admins of the largest companies plus random members) get
   Keycloak accounts linked to their seeded identities; their access tokens go to tokens.json.

Usage (from the repo root, stack running with load-test settings, see README.md):
    python3 load-tests/setup.py --companies 10000 --users 1000000 --sample 400
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

KC = os.environ.get("KC_URL", "http://localhost:8180")
CORE = os.environ.get("CORE_URL", "http://localhost:8080")
REALM = "occpl"
PASSWORD = "LoadTest-Passw0rd!2026"
STAFF_EMAIL = "loadtest-staff@occpl.loadtest"
STAFF_TOTP_SECRET = "loadtest-totp-secret-0123456789"  # raw secret as Keycloak stores it
HERE = os.path.dirname(os.path.abspath(__file__))
ENV_FILE = os.path.join(HERE, "..", "infra", "docker", ".env")
MYSQL = os.environ.get("MYSQL_CONTAINER", "occpl-mysql-1")


def env(name: str) -> str:
    if name in os.environ:
        return os.environ[name]
    with open(ENV_FILE) as f:
        for line in f:
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip()
    raise SystemExit(f"{name} not set")


def http(method, url, body=None, token=None, form=None, expect=(200, 201, 204)):
    data, headers = None, {}
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw and r.headers.get_content_type().endswith("json") else raw), r.headers
    except urllib.error.HTTPError as e:
        raw = e.read()
        if e.code in expect:
            return e.code, raw, e.headers
        raise SystemExit(f"{method} {url} → {e.code}: {raw[:500]!r}")


def sql(statement: str) -> str:
    out = subprocess.run(["docker", "exec", "-i", MYSQL, "mysql", "-uroot", "-p" + env("MYSQL_ROOT_PASSWORD"),
                          "-N", "-B", "occpl_core"], input=statement.encode(), capture_output=True, check=True)
    return out.stdout.decode()


def admin_token() -> str:
    _, r, _ = http("POST", f"{KC}/realms/master/protocol/openid-connect/token", form={
        "grant_type": "password", "client_id": "admin-cli",
        "username": env("KEYCLOAK_ADMIN_USERNAME"), "password": env("KEYCLOAK_ADMIN_PASSWORD")})
    return r["access_token"]


def totp(secret: str, period=30, digits=6) -> str:
    counter = struct.pack(">Q", int(time.time()) // period)
    mac = hmac.new(secret.encode(), counter, hashlib.sha256).digest()
    offset = mac[-1] & 0x0F
    code = (struct.unpack(">I", mac[offset:offset + 4])[0] & 0x7FFFFFFF) % (10 ** digits)
    return str(code).zfill(digits)


def ensure_flow(adm: str, alias: str, steps: list[tuple[str, str | None]]) -> str:
    base = f"{KC}/admin/realms/{REALM}/authentication"
    _, flows, _ = http("GET", f"{base}/flows", token=adm)
    existing = next((f for f in flows if f["alias"] == alias), None)
    if existing:
        return existing["id"]
    http("POST", f"{base}/flows", {"alias": alias, "providerId": "basic-flow", "topLevel": True, "builtIn": False}, adm)
    for provider, _ in steps:
        http("POST", f"{base}/flows/{urllib.parse.quote(alias)}/executions/execution", {"provider": provider}, adm)
    _, executions, _ = http("GET", f"{base}/flows/{urllib.parse.quote(alias)}/executions", token=adm)
    for execution, (provider, amr) in zip(executions, steps):
        execution["requirement"] = "REQUIRED"
        http("PUT", f"{base}/flows/{urllib.parse.quote(alias)}/executions", execution, adm)
        if amr:
            http("POST", f"{base}/executions/{execution['id']}/config",
                 {"alias": f"{alias}-{amr}", "config": {"default.reference.value": amr, "default.reference.maxAge": "0"}}, adm)
    _, flows, _ = http("GET", f"{base}/flows", token=adm)
    return next(f["id"] for f in flows if f["alias"] == alias)


def ensure_client(adm: str, client_id: str, flow_id: str) -> None:
    base = f"{KC}/admin/realms/{REALM}/clients"
    _, found, _ = http("GET", f"{base}?clientId={client_id}", token=adm)
    if found:
        http("DELETE", f"{base}/{found[0]['id']}", token=adm)  # recreate so its settings are current
    http("POST", base, {
        "clientId": client_id, "publicClient": True, "standardFlowEnabled": False, "directAccessGrantsEnabled": True,
        "attributes": {"access.token.lifespan": "3600"},
        "authenticationFlowBindingOverrides": {"direct_grant": flow_id},
        "protocolMappers": [
            {"name": "occpl-core-audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
             "config": {"included.custom.audience": "occpl-core", "access.token.claim": "true", "id.token.claim": "false"}},
            {"name": "amr", "protocol": "openid-connect", "protocolMapper": "oidc-amr-mapper",
             "config": {"access.token.claim": "true", "id.token.claim": "true", "introspection.token.claim": "true"}},
        ]}, adm)


def password_token(client_id: str, username: str, otp: str | None = None) -> str:
    form = {"grant_type": "password", "client_id": client_id, "username": username, "password": PASSWORD,
            "scope": "openid email profile"}
    if otp:
        form["totp"] = otp
    _, r, _ = http("POST", f"{KC}/realms/{REALM}/protocol/openid-connect/token", form=form)
    return r["access_token"]


def staff_token() -> str:
    """Keycloak accepts each TOTP code only once, also across runs: always use a fresh 30 s window."""
    time.sleep(30 - time.time() % 30 + 1)
    return password_token("occpl-loadtest-staff", STAFF_EMAIL, totp(STAFF_TOTP_SECRET))


def create_kc_user(adm: str, email: str, credentials: list) -> str:
    status, _, headers = http("POST", f"{KC}/admin/realms/{REALM}/users", {
        "username": email, "email": email, "emailVerified": True, "enabled": True, "firstName": "Load",
        "lastName": "Test", "credentials": credentials}, adm, expect=(201, 409))
    if status == 409:
        _, users, _ = http("GET", f"{KC}/admin/realms/{REALM}/users?exact=true&email={urllib.parse.quote(email)}", token=adm)
        return users[0]["id"]
    return headers["Location"].rsplit("/", 1)[1]


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--companies", type=int, default=10_000)
    p.add_argument("--users", type=int, default=1_000_000)
    p.add_argument("--sample", type=int, default=400, help="seeded users who get real Keycloak logins")
    p.add_argument("--skip-seed", action="store_true")
    a = p.parse_args()

    adm = admin_token()
    users_flow = ensure_flow(adm, "loadtest direct grant",
                             [("direct-grant-validate-username", None), ("direct-grant-validate-password", "pwd")])
    staff_flow = ensure_flow(adm, "loadtest direct grant otp",
                             [("direct-grant-validate-username", None), ("direct-grant-validate-password", "pwd"),
                              ("direct-grant-validate-otp", "otp")])
    ensure_client(adm, "occpl-loadtest-users", users_flow)
    ensure_client(adm, "occpl-loadtest-staff", staff_flow)

    otp_credential = {"type": "otp", "userLabel": "loadtest", "secretData": json.dumps({"value": STAFF_TOTP_SECRET}),
                      "credentialData": json.dumps({"subType": "totp", "digits": 6, "counter": 0, "period": 30,
                                                    "algorithm": "HmacSHA256"})}
    create_kc_user(adm, STAFF_EMAIL, [{"type": "password", "value": PASSWORD, "temporary": False}, otp_credential])
    staff = staff_token()
    http("GET", f"{CORE}/api/v1/me", token=staff)  # first login creates the platform user
    sql(f"""INSERT IGNORE INTO platform_role_assignment (user_id, role_id, assigned_at)
SELECT id, UUID_TO_BIN('01920000-0000-7000-8000-000000000001'), UTC_TIMESTAMP(6) FROM platform_user WHERE email = '{STAFF_EMAIL}';""")
    _, me, _ = http("GET", f"{CORE}/api/v1/me", token=staff)
    if "platform.products.manage" not in (me.get("platform") or {}).get("permissions", []):
        raise SystemExit(f"staff token does not carry platform permissions (amr/MFA?): {me}")

    if not sql("SELECT HEX(id) FROM product WHERE code = 'lt-product';").strip():
        _, product, _ = http("POST", f"{CORE}/api/v1/products", {
            "code": "lt-product", "name": "Load Test Product", "appUrl": "https://lt-product.occpl.test",
            "serviceClientId": "product-sample-service"}, staff)
        pid = product["id"]
        http("POST", f"{CORE}/api/v1/products/{pid}/features", {"code": "export", "name": "Export", "type": "BOOLEAN"}, staff)
        http("POST", f"{CORE}/api/v1/products/{pid}/features", {"code": "api-calls", "name": "API calls", "type": "LIMIT",
                                                              "unit": "API_CALLS", "resetPeriod": "MONTHLY"}, staff)
        http("POST", f"{CORE}/api/v1/products/{pid}/versions",
             {"config": {"schemaVersion": 1, "provisioning": {"mode": "NONE"}}}, staff)
        http("POST", f"{CORE}/api/v1/products/{pid}/versions/1/publish", None, staff)
        http("POST", f"{CORE}/api/v1/products/{pid}/activate", None, staff)
        _, plan, _ = http("POST", f"{CORE}/api/v1/plans", {"code": "lt-pro", "plan": {
            "name": "Load Test Pro", "billingPeriod": "MONTHLY", "trialDays": 0,
            "prices": [{"currency": "INR", "amountMinor": 99900}],
            "products": [{"productId": pid, "features": [{"code": "export"},
                                                          {"code": "api-calls", "limit": 1_000_000_000_000}]}]}}, staff)
        http("POST", f"{CORE}/api/v1/plans/{plan['id']}/publish", None, staff)

    if not a.skip_seed:
        started = time.time()
        script = subprocess.run([sys.executable, os.path.join(HERE, "seed", "seed_sql.py"), "--companies",
                                 str(a.companies), "--users", str(a.users)], capture_output=True, check=True).stdout
        print(sql(script.decode()).strip(), f"(seeded in {time.time() - started:.0f} s)")

    # Sample: admins of the 20 largest companies, then admins and members of random companies.
    rows = sql(f"""
(SELECT BIN_TO_UUID(u.id), u.email, BIN_TO_UUID(c.id), 'ADMIN', c.code FROM seed_user s
  JOIN platform_user u ON u.id = s.id JOIN seed_company sc ON sc.n = s.company_n JOIN company c ON c.id = sc.id
  WHERE s.idx = 0 AND sc.n <= 20)
UNION ALL
(SELECT BIN_TO_UUID(u.id), u.email, BIN_TO_UUID(c.id), IF(s.idx = 0, 'ADMIN', 'MEMBER'), c.code FROM seed_user s
  JOIN platform_user u ON u.id = s.id JOIN seed_company sc ON sc.n = s.company_n JOIN company c ON c.id = sc.id
  WHERE sc.n > 20 AND s.idx < 2 ORDER BY RAND(42) LIMIT {a.sample - 20});""")
    sample = []
    adm, adm_at = admin_token(), time.time()
    for line in rows.strip().splitlines():
        user_id, email, company_id, role, code = line.split("\t")
        if time.time() - adm_at > 40:  # admin-cli tokens live 60 s
            adm, adm_at = admin_token(), time.time()
        kc_id = create_kc_user(adm, email, [{"type": "password", "value": PASSWORD, "temporary": False}])
        sql(f"UPDATE user_identity SET subject = '{kc_id}' WHERE user_id = UUID_TO_BIN('{user_id}') AND provider = 'keycloak';")
        sample.append({"userId": user_id, "email": email, "companyId": company_id, "role": role, "companyCode": code,
                       "subject": kc_id})
    for s in sample:
        s["token"] = password_token("occpl-loadtest-users", s["email"])
    staff = staff_token()
    with open(os.path.join(HERE, "tokens.json"), "w") as f:
        json.dump({"createdAt": int(time.time()), "staff": staff, "users": sample,
                   "serviceClientId": "product-sample-service", "serviceClientSecret": env("PRODUCT_SAMPLE_CLIENT_SECRET")}, f)
    print(f"tokens.json: {len(sample)} users + staff (valid 1 h)")


if __name__ == "__main__":
    main()
