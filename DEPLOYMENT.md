# Deployment

Status: Local stack, Dockerfiles and CI implemented in Phase 2; sample product service added in Phase 7. Production deployment in Phase 10.

## 1. Environments

| Env | Purpose | Infrastructure | Data |
|---|---|---|---|
| local | Development | Docker Compose on developer machine | Synthetic seed |
| test (CI) | Automated tests | Testcontainers (MySQL, Keycloak, Redis) | Ephemeral |
| staging | Release verification, load tests | Same topology as prod, smaller sizes | Synthetic / anonymized |
| production | Customers | Ubuntu 24.04 LTS VMs (≥ 2 app nodes) behind LB/Nginx, managed or replicated MySQL 9.7 LTS, Redis, Keycloak (2 nodes) | Real |

Configuration: Spring profiles `local` and `test` exist (staging/prod use the production-safe defaults in
`application.yml` plus environment variables); the portal reads a runtime `config.json`
generated from environment variables. All secrets injected at runtime (env vars / secret manager); none in
Git or images.

### Local development

Prerequisites: Docker, JDK 25, Maven 3.9+, Node.js 24 LTS.

```bash
# Full stack in containers
cd infra/docker && cp .env.example .env   # edit the passwords
docker compose up --build
#   Portal http://localhost:4200 · API http://localhost:8080 · Swagger http://localhost:8080/swagger-ui.html
#   Health http://localhost:8081/actuator/health · Keycloak http://localhost:8180
#   Mail inbox (captures every outgoing email) http://localhost:8025
#   Sample product (Notes API) http://localhost:8090 · health http://localhost:8091/actuator/health

# Or run the backend from source against the compose MySQL/Keycloak
docker compose up -d mysql keycloak
cd backend/core-platform
SPRING_PROFILES_ACTIVE=local DB_USERNAME=occpl_app DB_PASSWORD=<app> \
  DB_MIGRATOR_USERNAME=occpl_migrator DB_MIGRATOR_PASSWORD=<migrator> mvn spring-boot:run

# Frontend dev server (uses public/config.json)
cd frontend/admin-portal && npm ci && npm start
```

Upgrading an existing local stack to Phase 7: the sample product's database and accounts are
created by `infra/docker/mysql/01-create-users.sh`, which MySQL runs only for a new volume, and the
new Keycloak clients come from the realm import, which runs only for a new realm. Add the new
`.env` entries (see `.env.example`) and recreate the stack with `docker compose down -v` (this deletes
local data).

Checks a contributor runs before pushing: `mvn verify` in `backend/` (needs Docker for
Testcontainers; includes the Core ↔ sample product end-to-end test in `backend/e2e-tests`) and `npm run format:check && npx ng lint && npm test && npx ng build` in
`frontend/admin-portal`.

### Core Platform configuration (environment variables)

| Variable | Required | Purpose |
|---|---|---|
| `DB_URL` | yes | JDBC URL of `occpl_core` |
| `DB_USERNAME` / `DB_PASSWORD` | yes | DML-only application account |
| `DB_MIGRATOR_USERNAME` / `DB_MIGRATOR_PASSWORD` | yes (prod) | DDL account used by Flyway |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | no (20 / 5) | HikariCP sizing per instance |
| `OIDC_ISSUER_URI` | yes | Expected `iss` (and discovery URL unless a JWK set URI is given) |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI` | no | Internal JWKS URL when the issuer URL is not reachable from the container |
| `OIDC_AUDIENCE` | no (`occpl-core`) | Required `aud` |
| `CORS_ALLOWED_ORIGINS` | no (none) | Comma-separated portal origins |
| `API_DOCS_ENABLED` | no (`false`) | OpenAPI/Swagger UI; keep `false` in production |

| `KEYCLOAK_BASE_URL` / `KEYCLOAK_REALM` | yes (prod) | Keycloak Admin REST API (internal URL) |
| `KEYCLOAK_ADMIN_CLIENT_ID` / `KEYCLOAK_ADMIN_CLIENT_SECRET` | yes (prod) | Service account `occpl-core-admin`; both URL and secret must be set or IdP admin features are disabled |
| `MFA_PLATFORM_REQUIRED` | no (`true`) | Withhold platform permissions without MFA; never disable in production |
| `BOOTSTRAP_SUPER_ADMIN_EMAIL` | first setup only | See "First Super Admin" below |
| `REDIS_URL` | yes (prod) | Rate limiting store |
| `RATE_LIMIT_ENABLED` | no (`true`) | |
| `RATE_LIMIT_USER_PER_MINUTE` / `RATE_LIMIT_ANONYMOUS_PER_MINUTE` | no (600 / 60) | |
| `OPENAPI_OIDC_ISSUER` | no | Browser-facing realm URL; enables "Authorize" in Swagger UI (non-production) |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` / `SMTP_AUTH` / `SMTP_STARTTLS` | yes (prod) | Outgoing email (invitations); use TLS and credentials in production |
| `MAIL_FROM` | no | Sender, default `OCCPL Innovations <no-reply@occpl.local>` |
| `INVITATION_ACCEPT_URL` | yes (prod) | Portal page that accepts invitations (`?token=` appended); must be a valid redirect URI of the portal client in Keycloak |
| `INVITATION_VALIDITY` | no (`7d`) | |
| `SUBSCRIPTION_GRACE_PERIOD` / `SUBSCRIPTION_SUSPENDED_EXPIRY` / `SUBSCRIPTION_DATA_RETENTION` | no (`7d` / `90d` / `90d`) | Subscription lifecycle timing (decision D-5); the retention period also delays deprovisioning in products |
| `PROVISIONING_CLIENT_ID` / `PROVISIONING_CLIENT_SECRET` | yes (prod) | Core's service client `occpl-core-services` for calls to product services; without a secret, provisioning calls fail and are retried |
| `PROVISIONING_TOKEN_URI` | no | Token endpoint; default `${KEYCLOAK_BASE_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token` |
| `PROVISIONING_CALLBACK_BASE_URL` | for ASYNC products | Core's URL as reachable by product services (sent as `callbackUrl`) |
| `PROVISIONING_CALLBACK_TIMEOUT` | no (`15m`) | How long an ASYNC product may take to report completion |
| `PROVISIONING_ALERT_RECIPIENTS` | yes (prod) | Comma-separated emails notified when a provisioning job fails permanently |
| `RATE_LIMIT_SERVICE_PER_MINUTE` | no (6000) | Per product service client |
| `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` | for billing | Razorpay API keys (Dashboard → Account & Settings → API Keys); test-mode keys outside production. Billing is disabled until both are set |
| `RAZORPAY_WEBHOOK_SECRET` | for billing | Secret entered when creating the webhook in the Razorpay dashboard; without it the webhook endpoint answers 503 |
| `RAZORPAY_API_URL` | no (`https://api.razorpay.com`) | Only for tests/stubs |
| `CATALOG_ALLOW_LOCAL_URLS` | no (`false`) | Development only: product URLs on localhost/127.0.0.1 and plain HTTP to them |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | no (private and loopback ranges) | Regex of proxy addresses whose `X-Forwarded-*` headers are trusted; set to the load balancer's addresses in production |
| `BILLING_INVOICE_LEAD_TIME` / `BILLING_PAYMENT_WINDOW` | no (`7d` / `14d`) | Issue renewal invoices this long before the period ends; unpaid invoices expire after the window |

### Razorpay setup (billing)

1. Create API keys and set `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` (test mode first).
2. Dashboard → Webhooks → Add: URL `https://<core-host>/api/v1/webhooks/payments/razorpay`, a strong
   secret (→ `RAZORPAY_WEBHOOK_SECRET`), events `invoice.paid`, `invoice.partially_paid`,
   `invoice.expired`, `payment.captured`, `payment.failed`. The URL must be reachable from the
   internet over HTTPS; Nginx must pass the body unchanged (signature is over the raw body).
3. Every company that pays needs a BILLING (or PRIMARY) contact with an email — the invoice is sent there.
4. **Smoke test before going live (BACKLOG B-3, risk R-5):** with test keys, issue an invoice for a
   test company (Subscription tab → "Issue invoice for next period"), pay it on the Razorpay page with
   a test card, and check that the invoice becomes PAID with "Applied" and the subscription period
   moves on. Also confirm the webhook delivery shows 200 in the dashboard. The adapter has so far
   been tested only against a stub of Razorpay's documented API.
5. Decide D-11 (GST) before taking live payments.

Admin portal container: `API_BASE_URL`, `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `CSP_CONNECT_SRC`
(space-separated origins of the API and IdP). The portal's origin must be a valid redirect URI
(`<origin>/*`) and post-logout redirect URI of the `occpl-admin-portal` client in Keycloak, and an
allowed CORS origin of Core (`CORS_ALLOWED_ORIGINS`).

### Using the admin portal locally

Open http://localhost:4200 and sign in with the first Super Admin (see below). The first sign-in
shows "Sign in with authenticator": Keycloak then asks to set up an authenticator app, and the next
sign-in with a code unlocks the platform area. Company administrators receive an email (Mailpit,
http://localhost:8025) to set their password; the link brings them back to the portal, which
accepts the invitation and opens their company.

### Sample product configuration (environment variables)

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USERNAME`/`DB_PASSWORD`, `DB_MIGRATOR_USERNAME`/`DB_MIGRATOR_PASSWORD` | Its own database (`occpl_sample`) and least-privilege accounts |
| `OIDC_ISSUER_URI`, `OIDC_AUDIENCE` (`product-sample`) | Token validation |
| `PROVISIONING_CLIENT_ID` (`occpl-core-services`) | The only client allowed to provision |
| `CORE_BASE_URL`, `CORE_TOKEN_URI`, `CORE_CLIENT_ID` (`product-sample-service`), `CORE_CLIENT_SECRET` | Calls to Core's service API |
| `PORT` / `MANAGEMENT_PORT` | 8090 / 8091 |

### Registering a product service

1. In Keycloak (realm `occpl`): create the product's confidential client (service account, audience
   mapper `occpl-core`), a client for its users (audience = the product's `apiAudience`), and a
   client scope **named exactly like the product's `apiAudience`** with an audience mapper for it,
   added to `occpl-core-services` as an **optional** client scope (Core requests it per call, so its
   token is valid at that product only). The local realm import contains these for the sample product.
2. In Core: `POST /api/v1/products` with `apiAudience`, `apiBaseUrl` and `serviceClientId`; add
   features; create and publish a configuration version with `provisioning` (and `launch`); activate.
3. Assign a plan containing the product to a company; Core provisions the company within seconds
   (job list: `GET /api/v1/platform/provisioning-jobs`).

Sample product locally: register it with `appUrl` `http://localhost:8090` (browser) and
`apiBaseUrl` `http://product-sample.localhost:8090` (compose network alias reachable from Core; plain
HTTP is accepted only for localhost hosts), `apiAudience` `product-sample`, `serviceClientId`
`product-sample-service`, a LIMIT feature `notes` (unit RECORDS, reset NONE) and configuration
`{"schemaVersion":1,"launch":{"path":"/companies/{companyId}"},"provisioning":{"mode":"SYNC","path":"/provisioning/v1/tenants/{companyId}"}}`.
The sample has no user interface yet; its API is `GET|POST /api/companies/{companyId}/notes` and
`DELETE /api/companies/{companyId}/notes/{noteId}` with a user token from client `product-sample-web`.

### First Super Admin (one-time)

1. Set `BOOTSTRAP_SUPER_ADMIN_EMAIL` on the Core Platform and restart it.
2. In the Keycloak admin console (realm `occpl`) create that user with first/last name, mark the
   email as verified and set a password (or send the user an "update password" email).
3. The user signs in (portal or Swagger UI). Core grants SUPER_ADMIN, writes an audit entry and
   Keycloak asks the user to set up an authenticator app at the next login. Platform permissions
   apply only after signing in with the authenticator code.
4. Remove `BOOTSTRAP_SUPER_ADMIN_EMAIL`. Further Super Admins are assigned through
   `PUT /api/v1/platform/users/{userId}/roles`.

Keycloak itself must have SMTP configured in every environment (realm settings → Email); the
local realm import points it at Mailpit. Production: configure the provider's SMTP host and
credentials in the realm, not in the realm import file.

### Trying the API locally

Open http://localhost:8080/swagger-ui.html → **Authorize** → *keycloak (OAuth2, authorizationCode)*
→ tick `openid` → sign in. The local realm allows this redirect only for development.

## 2. Initial production topology

```
Internet → LB / Nginx (TLS, static portal, reverse proxy) ─┬─ core-platform #1 ─┐
                                                            └─ core-platform #2 ─┼─ MySQL primary (+ replica, PITR backups)
                                       Keycloak ×2 ──── own DB                   ├─ Redis
                                       product services (own hosts/DBs)          └─ SMTP provider
Monitoring: Prometheus + Grafana + Alertmanager, Loki (logs); error tracking (e.g. Sentry)
```

### Hostnames (decision D-12: domain `occplsoftech.com`)

| Host | Serves | Notes |
|---|---|---|
| `admin.occplsoftech.com` | Admin portal (Super Admin and Company Admin) | Portal `API_BASE_URL`, `OIDC_ISSUER`; Keycloak redirect URI `https://admin.occplsoftech.com/*`; Core `CORS_ALLOWED_ORIGINS`, `INVITATION_ACCEPT_URL=https://admin.occplsoftech.com/invitations/accept` |
| `api.occplsoftech.com` | Core Platform API (`/api/v1/**`) | Razorpay webhook `https://api.occplsoftech.com/api/v1/webhooks/payments/razorpay`; `PROVISIONING_CALLBACK_BASE_URL`; problem `type` URIs and the product-config schema `$id` use this host |
| `auth.occplsoftech.com` | Keycloak (realm `occpl`) | `OIDC_ISSUER_URI=https://auth.occplsoftech.com/realms/occpl`; admin console reachable only from the operations network |
| `<product>.occplsoftech.com` | Product services (e.g. `notes.occplsoftech.com`) | Each product's `appUrl`; its API may sit on the same host or `<product>-api.` |
| `*.staging.occplsoftech.com` | Staging (`admin.staging.…`, `api.staging.…`, `auth.staging.…`) | Separate realm, database and secrets |
| `occplsoftech.com` | Company website (not part of this platform) | |

TLS: one certificate per host or a wildcard `*.occplsoftech.com` (+ `*.staging.occplsoftech.com`),
automatic renewal with expiry alert (§6). HSTS with `includeSubDomains` is sent by Core and must be
sent by Nginx for the portal — only enable `preload` once every subdomain serves HTTPS.
Email: sender `no-reply@occplsoftech.com` (`MAIL_FROM`) with SPF, DKIM and DMARC records for the
chosen email provider.

Kubernetes is not used initially (ADR-0004 rationale); the containers are k8s-ready
(stateless, health probes, env config) if operational needs later justify it.

## 3. Build & release pipeline (CI/CD)

1. PR: `mvn verify` (unit + integration with Testcontainers, module verification),
   `npm ci && ng lint && ng test --watch=false && ng build`, dependency scan, Flyway validate.
2. Main: build versioned images (`core-platform:<git-sha>`, `admin-portal:<git-sha>`), Trivy
   scan, push to registry.
3. Deploy staging: run Flyway migration job (migrator credentials) → rolling restart of app
   nodes → smoke tests → (optional) load test.
4. Promote the same image digests to production with manual approval.

## 4. Release & rollback

- Migrations are expand/contract and backward compatible, so the previous app version runs on
  the new schema. Rollback = redeploy previous image digest; DB is not rolled back.
- Destructive (contract) migrations ship only in a release after the code no longer uses the
  old structure.
- Rolling deploy: drain node from LB (readiness DOWN) → stop → start new → readiness UP.

## 5. Backups & recovery (drill done in Phase 9: infra/backup/DRILL.md)

Scripts in `infra/backup/` (MySQL Shell from the `mysql:9.7` image, so client and server versions match;
account `occpl_backup`, password on stdin):

| When | Script | What |
|---|---|---|
| Nightly | `backup.sh /backups` | Consistent parallel dump of `occpl_core` and `occpl_sample` → `/backups/<UTC>-full/` (1.08 GB database → 39 MB in 5 s in the drill) |
| Every 5 min | `binlog-backup.sh /backups` | Appends the binary logs since the last increment to `/backups/<newest full>-binlog/` (1–2 s) → RPO ≤ 5 min + copy delay |
| Recovery | `restore.sh /backups [<full>]` | Into an **empty** server: newest full backup, then all increments; `STOP_BEFORE=<GTID>` stops right before a bad transaction |

Recovery runbook:
1. Provision an empty MySQL 9.7 server (same settings as compose: utf8mb4, UTC, binary log + GTIDs on).
2. Copy the backup directories from off-host storage; run `restore.sh` (with `STOP_BEFORE` when
   undoing a mistake: find the GTID with `SHOW BINLOG EVENTS` / the binlog dump's JSON index).
3. Recreate the database accounts (`infra/docker/mysql/01-create-users.sh` or the production secrets
   tooling) — accounts are not part of the backup.
4. Point Core and the products at the server; Flyway validates the schema on start-up.
5. Take a new full backup immediately (the GTID history continues).

Measured in the drill (1 M users): restore 41–43 s, Core ready 14 s later; point-in-time recovery to
the transaction before an accidental DELETE exact. Targets RPO 15 min / RTO 4 h are met with margin;
remaining for Phase 10: off-host copies with retention and encryption, timers and alerts, the
production Keycloak database, a drill on production hardware.

## 6. Monitoring & alerts (initial set)

| Alert | Condition |
|---|---|
| API error rate | 5xx > 1% for 5 min |
| Latency | p95 > SLO for 10 min |
| DB pool | Hikari pending > 0 for 5 min or active/max > 90% |
| Provisioning | any job FAILED_PERMANENT; FAILED_RETRYABLE backlog > threshold |
| Subscription jobs | scheduled sweep failed or not run in expected window |
| Outbox lag | oldest unpublished event > 5 min |
| Webhooks | signature failures spike |
| Infra | disk > 80%, replica lag > 60 s, certificate expiry < 14 days |
