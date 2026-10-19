# Deployment

Status: Local stack, Dockerfiles and CI implemented in Phase 2; sample product service added in Phase 7. Production deployment in Phase 10.

## 1. Environments

| Env | Purpose | Infrastructure | Data |
|---|---|---|---|
| local | Development | Docker Compose on developer machine | Synthetic seed |
| test (CI) | Automated tests | Testcontainers (MySQL, Keycloak, Redis) | Ephemeral |
| local | Development, first check of a release | Docker Compose on the developer machine (`infra/docker`) | Synthetic seed |
| dev (test) | Every release is deployed here first (D-17) | One EC2 instance (`t3.large`) with the same stack as production (`infra/vps`, `infra/aws/dev`), `*.dev.occplsoftech.com` | Test data |
| production | Customers | **One VPS** with Docker Compose (D-16, ADR-0012, `infra/vps`); the AWS design of ADR-0011 (`infra/aws`) is ready for later | Real |

Configuration: Spring profiles `local` and `test` exist (production uses the production-safe defaults in
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
| `CORS_ALLOWED_ORIGINS` | no (none) | Comma-separated origins (portal, plus the public website for `/api/v1/public/leads`). On the VPS the portal origin is built in; add others with `CORS_EXTRA_ORIGINS` in `.env` |
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

Admin portal container: `API_BASE_URL` (including `/api/v1`, e.g. `https://api.occplsoftech.com/api/v1`), `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `CSP_CONNECT_SRC`
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

## 2. Production topology

**Launch target: one VPS (ADR-0012, `infra/vps`, operations in `infra/vps/RUNBOOK.md`).**

```
Internet ─ :80/:443 ─ Caddy (Let's Encrypt, HTTP → HTTPS)
   admin.occplsoftech.com → admin-portal · api.occplsoftech.com → core-platform
   auth.occplsoftech.com  → keycloak (/admin and the master realm only from ADMIN_IPS)
   internal: MySQL 8.4 (occpl_core, keycloak) · Redis; backups → /var/backups/occpl → S3 (write-only user)
```

DNS stays in Route 53 (three A records to the server). Email uses the `tech@occplsoftech.com` SMTP
mailbox until SES is approved. One machine means an outage on reboot or host failure; recovery is a
restore (RUNBOOK §6).

### Later: AWS (ADR-0011, `infra/aws`)

```
Route 53 (occplsoftech.com) → AWS WAF → Application Load Balancer (ACM *.occplsoftech.com, ap-south-1)
     admin.  ─┐   host-based routing
     api.    ─┼─→ EC2 #1 (AZ a) ┐  Docker Compose: core-platform, admin-portal (nginx), keycloak
     auth.   ─┘   EC2 #2 (AZ b) ┘  Auto Scaling group (fixed size 2), SSM only, no SSH
                       │
     private subnets ──┼─ RDS MySQL 8.4 LTS Multi-AZ (occpl_core, keycloak) · backups + PITR 35 days
                       ├─ ElastiCache (Valkey/Redis OSS, TLS + AUTH, 1 replica)
                       └─ SES (SMTP), Secrets Manager, ECR, CloudWatch Logs / metrics / alarms
Product services: own hosts and databases, reached at <product>.occplsoftech.com
```

Dev/test environment (D-17): the same stack on one EC2 instance at `*.dev.occplsoftech.com`
(`infra/aws/dev`, `infra/vps/RUNBOOK.md` §9); releases go there first. No staging on the full AWS
stack (D-15 superseded).
Kubernetes and ECS are not used initially (ADR-0004, ADR-0011); the containers would move unchanged.

### Hostnames (decision D-12: domain `occplsoftech.com`)

| Host | Serves | Notes |
|---|---|---|
| `admin.occplsoftech.com` | Admin portal (Super Admin and Company Admin) | Portal `API_BASE_URL`, `OIDC_ISSUER`; Keycloak redirect URI `https://admin.occplsoftech.com/*`; Core `CORS_ALLOWED_ORIGINS`, `INVITATION_ACCEPT_URL=https://admin.occplsoftech.com/invitations/accept` |
| `api.occplsoftech.com` | Core Platform API (`/api/v1/**`) | Razorpay webhook `https://api.occplsoftech.com/api/v1/webhooks/payments/razorpay`; `PROVISIONING_CALLBACK_BASE_URL`; problem `type` URIs and the product-config schema `$id` use this host |
| `auth.occplsoftech.com` | Keycloak (realm `occpl`) | `OIDC_ISSUER_URI=https://auth.occplsoftech.com/realms/occpl`; admin console reachable only from the operations network |
| `<product>.occplsoftech.com` | Product services (e.g. `notes.occplsoftech.com`) | Each product's `appUrl`; its API may sit on the same host or `<product>-api.` |
| `*.dev.occplsoftech.com` | Dev/test server (`admin.dev.…`, `api.dev.…`, `auth.dev.…`) | Own database and realm (D-17) |
| `occplsoftech.com` | Company website (not part of this platform) | |

TLS: one certificate per host or a wildcard `*.occplsoftech.com`,
automatic renewal with expiry alert (§6). HSTS with `includeSubDomains` is sent by Core and must be
sent by Nginx for the portal — only enable `preload` once every subdomain serves HTTPS.
Email: sender `no-reply@occplsoftech.com` (`MAIL_FROM`) with SPF, DKIM and DMARC records for the
chosen email provider.

Kubernetes is not used initially (ADR-0004 rationale); the containers are k8s-ready
(stateless, health probes, env config) if operational needs later justify it.

## 3. Build & release pipeline (CI/CD)

0. **What changed decides the work** (`infra/scripts/changed-paths.sh`): CI runs only the backend, frontend and infra jobs
   whose files changed (a pipeline change runs everything), and Release re-tags an image whose sources did not change
   from the previous release instead of rebuilding it (`infra/scripts/retag-image.sh`: same digest, so the server leaves
   that container running; a missing earlier image is built).
1. **Every PR and push** (`.github/workflows/ci.yml`): backend `mvn verify` on MySQL 8.4 and 9.7
   (unit, Testcontainers integration, Modulith verification, Core ↔ sample end-to-end); frontend
   format, lint, tests, production build, `npm audit`; image builds with a Trivy scan; infra job
   (`terraform fmt`/`validate` of every stack, production-realm check, shellcheck, compose config).
2. **Main, after CI passed** (`.github/workflows/release.yml`): build and push `core-platform`,
   `admin-portal` and `keycloak` to the GitHub container registry, tagged with the commit SHA
   (a release always means the same images); the production realm file is checked against the dev realm.
3. **Dev server (automatic, D-17)**: the same steps on the EC2 test server (`dev` environment), then the
   smoke test; a failure stops the pipeline before production. Try the changed features there.
4. **Production** (once `DEPLOY_PRODUCTION` is set): after approval by the `production` environment's
   reviewers: copy `infra/vps` to the server over SSH, `bin/deploy.sh <sha>` (pull, start, wait until Keycloak, Core and the portal are
   ready; automatic rollback to the previous release on failure) → `infra/scripts/smoke-test.sh`
   through the public addresses.
5. **Redeploy / rollback**: run *Release* manually with a release SHA (infra/vps/RUNBOOK.md §7).
   (`deploy-aws.yml` is the dormant manual workflow for the AWS stack.)

## 4. Release & rollback

- Migrations are expand/contract and backward compatible, so the previous app version runs on
  the new schema. Rollback = redeploy the previous release; the database is not rolled back.
- Destructive (contract) migrations ship only in a release after the code no longer uses the
  old structure.
- **VPS (launch):** Flyway runs when Core starts (migrator account). `bin/deploy.sh` restarts the
  containers (about 30 s to 1.5 min of downtime; one server), waits for readiness and rolls back to
  the previous release if it does not become ready. Risky migrations go out outside business hours.
- **AWS (later):** Flyway runs when Core starts on the first instance of a rollout (Flyway's lock
  prevents concurrent runs); the other instances find the schema up to date. Rolling deploy: deregister the instance from all target groups (connections drain) → deploy
  through SSM (`deploy.sh`: config from SSM/Secrets Manager, `docker compose pull && up`) →
  wait until Keycloak, Core and the portal report ready → register. If an instance fails, all
  instances already updated return to the previous release and the run fails.
- Production keeps one of two instances serving throughout. Without an AWS staging environment,
  differences between local compose and AWS (RDS, ElastiCache TLS, SES, the load balancer) first
  show in production: the smoke test and the automatic rollback limit the impact, and releases with
  risky migrations go out outside business hours.
- New instances (instance refresh, replacement) boot the release recorded in
  `/occpl/production/release` from the bundle in S3.

## 5. Backups & recovery

**VPS (launch):** `infra/vps/bin/backup.sh` (cron): nightly full dump of `occpl_core` and `keycloak` at
02:00 IST and a binary-log increment every 5 minutes with the scripts below, 7 days locally, a copy
in the write-only S3 bucket (35 days; `infra/aws/shared/backups.tf`), heartbeat pings for missed runs.
Recovery onto a new server: `infra/vps/RUNBOOK.md` §6 (rehearsed: dump 14 s, restore 6–8 s for the
test data, incremental changes included). The backup tools use MySQL Shell 9.7 (`util.dumpBinlogs`)
against the 8.4 server.


**Production (AWS, primary):** RDS automated backups with point-in-time restore (35 days); AWS Backup daily copy at 02:00 IST, copied to ap-south-2 (Hyderabad)
for production; failed backup jobs alert through SNS. Procedures: infra/aws/RUNBOOK.md §3. The
Keycloak database is on the same RDS instance and is covered by the same backups.

**Self-managed MySQL** (local, or a deployment outside RDS; drill done in Phase 9:
infra/backup/DRILL.md). Scripts in `infra/backup/` (MySQL Shell 9.7 from the `mysql:9.7` image (only 9.x has
`util.dumpBinlogs`; it dumps and loads 8.4 servers); account `occpl_backup`, password on stdin):

| When | Script | What |
|---|---|---|
| Nightly | `backup.sh /backups` | Consistent parallel dump of `occpl_core` and `occpl_sample` → `/backups/<UTC>-full/` (1.08 GB database → 39 MB in 5 s in the drill) |
| Every 5 min | `binlog-backup.sh /backups` | Appends the binary logs since the last increment to `/backups/<newest full>-binlog/` (1–2 s) → RPO ≤ 5 min + copy delay |
| Recovery | `restore.sh /backups [<full>]` | Into an **empty** server: newest full backup, then all increments; `STOP_BEFORE=<GTID>` stops right before a bad transaction |

Recovery runbook (self-managed):
1. Provision an empty MySQL server (same settings as compose: utf8mb4, UTC, binary log + GTIDs on,
   `log_bin_trust_function_creators=1` for the audit-log triggers).
2. Copy the backup directories from off-host storage; run `restore.sh` (with `STOP_BEFORE` when
   undoing a mistake: find the GTID with `SHOW BINLOG EVENTS` / the binlog dump's JSON index).
3. Recreate the database accounts (`infra/docker/mysql/01-create-users.sh`, or
   `infra/aws/compose/bin/bootstrap-db.sh`): accounts are not part of the backup.
4. Point Core and the products at the server; Flyway validates the schema on start-up.
5. Take a new full backup immediately (the GTID history continues).

Measured in the drill (1 M users): restore 41–43 s, Core ready 14 s later; point-in-time recovery to
the transaction before an accidental DELETE exact. Targets RPO 15 min / RTO 4 h.

## 6. Monitoring & alerts

**VPS (launch):** `bin/healthcheck.sh` every 5 minutes checks Keycloak, Core and the portal and the
disk (85%) and pings `HEARTBEAT_URL` (healthchecks.io emails when the pings stop); an external uptime
monitor checks `https://admin.occplsoftech.com/healthz`; `BACKUP_PING_URL` reports missed backups. Logs:
`docker compose logs`, rotated by Docker (20 MB × 5 per container). Metrics dashboards and alarms such as
those below arrive with the move to AWS.

**AWS (later):** CloudWatch → SNS email, `infra/aws/modules/platform/monitoring.tf`.

Logs: every container to CloudWatch Logs (`/occpl/<env>/<service>`), plus the deploy log; ALB
access logs to S3; VPC flow logs (rejected traffic). Metrics: ALB, RDS, ElastiCache, instance
disk/memory (CloudWatch agent) and Core's Prometheus metrics (scraped by the agent into the
`OCCPL/<env>` namespace).

| Alarm | Condition |
|---|---|
| `api-5xx-rate` | Core 5xx > 1% of requests for 5 min |
| `api-latency-p95` | p95 > 300 ms for 10 min (SLO from the Phase 9 load test) |
| `<service>-unhealthy`, `<service>-down` | unhealthy targets / no healthy target (core, portal, keycloak) |
| `instances-missing` | fewer in-service instances than configured |
| `disk-80` | root volume > 80% |
| `db-cpu`, `db-free-storage`, `db-freeable-memory`, `cache-cpu` | database / cache resources |
| `db-pool-pending` | Hikari threads waiting for a connection for 5 min |
| `provisioning-failed` | any provisioning job failed permanently |
| `webhook-rejections` | > 10 rejected payment webhooks in 5 min (bad secret or attack) |
| `rate-limiter-down` | Core logs that it cannot reach Redis (rate limiting fails open) |
| `certificate-expiry` | ACM certificate < 14 days |
| Backup failed | AWS Backup job failed / expired (EventBridge) |

Not yet alarmed: outbox lag and the subscription sweep window (no metric exported yet; BACKLOG).
