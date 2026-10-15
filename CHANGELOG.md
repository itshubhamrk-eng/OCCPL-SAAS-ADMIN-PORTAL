# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Portal redesign: "Sunrise Mesh Glass" (2026-10-15, D-18)
- Design chosen by the owner from 24 sample boards: crimson + indigo on light grey, frosted-glass cards, crimson side menu with icons,
  header bar (environment chip on dev/local, user pill, sign out), colourful dashboard tiles and a companies-by-status donut built from real totals.
- Implemented through design tokens (`styles/_tokens.scss`), global components, the shell layout and the dashboard; automatic dark mode kept.
  System fonts only (the CSP allows no external font host). No fake features: no search box, bell or assistant until the backend has them.
- Mobile: the menu becomes a drawer. Component-style warning budget raised from 4 kB to 6 kB for the shell.

### Dev server live; CI fixes (2026-10-15)
- First release deployed to the EC2 dev server by the pipeline; sign-in with MFA verified at admin.dev.occplsoftech.com.
- CI fixes found on the first GitHub runs: shellcheck SC2015 rewrites, AWS compose check with empty env-file stand-ins, Trivy installed
  from the checksum-verified release binary (go install failed on Go 1.26); `init-env.sh` suggests mailbox addresses on the main domain.
- The 10 Dependabot PRs opened by the first push are not merged (major version jumps need testing).

### Dev/test server on EC2 (2026-10-15, D-17)
- `infra/aws/dev`: one `t3.large` (Ubuntu 24.04, default VPC, fixed address, firewall, S3-backup role,
  Route 53 records for `*.dev.occplsoftech.com`) running the VPS stack; `release.yml` now deploys every
  release to the `dev` environment first and to production (approval, `DEPLOY_PRODUCTION`) afterwards.
  `install.sh` also works on EC2 (uses the sudo user's SSH keys). Replaces D-15.

### Single-VPS deployment (2026-10-15, D-16, ADR-0012)
- `infra/vps`: Caddy (automatic HTTPS), MySQL 8.4, Redis, Keycloak (production image), Core, portal on one
  server; scripts for server preparation, `.env` generation, deploy with automatic rollback, backups
  with off-host copy, heartbeat; runbook. `release.yml` publishes images to GHCR and deploys over SSH
  after approval; the AWS workflow is manual-only (`deploy-aws.yml`). CI checks the new compose file,
  Caddyfile and scripts.
- Core: `SMTP_SSL` (implicit TLS) alongside STARTTLS; the production realm takes `KC_SMTP_STARTTLS`/`KC_SMTP_SSL`.
- Backup tools default to the MySQL Shell 9.7 client (8.4 shell has no `dumpBinlogs`); verified against an 8.4 server.
- Terraform (shared stack): S3 bucket and write-only user for off-host backups.
- `smoke-test.sh` moved to `infra/scripts/` and accepts Caddy's 308 redirect.

### No AWS staging environment (2026-10-14)
- Decision D-15: production is the only AWS environment; releases are tried on the local Docker
  Compose stack and approved in GitHub before the rolling deploy. Removed `infra/aws/envs/staging`
  and the staging job of the Deploy workflow; runbook and deployment docs updated.

### Phase 10 — Deployment (2026-10-14)
- AWS infrastructure as Terraform (`infra/aws`): state bucket; shared stack (ECR with immutable
  tags, artifacts bucket, GitHub OIDC build role, SES identity with DKIM/MAIL FROM/DMARC and SMTP
  credentials); environment module (VPC over two AZs, ALB with ACM and WAF, Keycloak admin console
  restricted to `admin_cidrs`, EC2 Auto Scaling group reached through SSM, RDS MySQL 8.4 with TLS-only
  accounts, ElastiCache Valkey with TLS, Secrets Manager, CloudWatch alarms, AWS Backup with a
  cross-region copy for production); staging and production stacks.
- Production runtime: compose bundle (Core, portal, Keycloak) with config rendered from SSM and
  Secrets Manager on the instance; production Keycloak image (optimized MySQL build, clustered with
  jdbc-ping, realm import generated from the development realm: TLS required, SES SMTP, production
  redirect URIs, no sample-product clients).
- Pipeline: `.github/workflows/deploy.yml` publishes images per commit, rolls them out to staging,
  smoke-tests, then production after approval; manual redeploy/rollback; failed rollouts return all
  updated instances to the previous release. CI `infra` job: Terraform fmt/validate, realm check,
  shellcheck, compose config.
- `audit_log` is append-only for every account (database triggers).
- Fix found in the production rehearsal: the portal's `API_BASE_URL` must include `/api/v1`.
- RUNBOOK.md: first-time setup, deploy/rollback, RDS point-in-time restore, region loss,
  incidents, secret rotation, maintenance.

### AWS hosting decisions (2026-10-14)
- D-13: AWS `ap-south-1` (Mumbai), EC2 instances with Docker Compose behind an Application Load
  Balancer; D-14: Amazon RDS for MySQL 8.4 LTS (RDS offers 9.7 only in preview). ADR-0011.
- Tests now default to MySQL 8.4 (`-Doccpl.test.mysql-image` overrides); CI runs the backend suite on
  8.4 and 9.7. Full suite verified on 8.4 (80 unit, 77 integration, sample, end-to-end). Local compose
  and backup scripts use 8.4.

### Production domain (2026-10-14)
- Decision D-12: `occplsoftech.com`; hostname plan in DEPLOYMENT.md §2. Problem-detail `type` URIs
  and the product-configuration schema `$id` now use `https://api.occplsoftech.com/` (previously a
  placeholder domain not owned by OCCPL; changed before any client depends on them).

### Phase 9 — Hardening (2026-10-14)
- Tenant isolation: endpoint-inventory test over every request mapping; deny-by-default guard for
  company paths before the body is read (another company's endpoints always answer 404).
- Security review fixes: seat limit bypass via re-sent/accepted invitations of deactivated members;
  re-invites require holding the member's current roles; per-product service tokens (optional
  Keycloak client scope per product audience); X-Forwarded-For handled by Tomcat's RemoteIpValve;
  webhook body capped at 64 KB and underpaid invoices not marked paid; product URLs refuse local
  and link-local addresses outside development (`CATALOG_ALLOW_LOCAL_URLS`); service access checks
  the entitlement before revealing membership; invitation delivery errors no longer log URLs.
- Dependencies: Tomcat 11.0.27, Jackson 3.1.7 / 2.21.7 (critical/high CVEs); Trivy image scan in
  CI; Dependabot for all Maven modules and images.
- Portal: every nginx location sends the full security header set.
- Load tests (k6, 10k companies / 1M users): SLOs met up to 210 req/s on the development VM;
  member search rewritten (p95 507 → 54 ms, new index on `user_profile.display_name`); database pool
  exhaustion answers 503 `SERVICE_BUSY` with `Retry-After`.
- Backups: MySQL Shell full dumps + binary-log increments, restore script with point-in-time
  recovery, least-privilege backup account; drill with measured RPO/RTO.
- Fixed: Spring Modulith Moments switched on scheduled jobs even with scheduling disabled.

### Billing — Razorpay invoices and webhooks (2026-10-14)
- New `billing` module: `PaymentGateway` port with a Razorpay adapter (Invoices API: create, fetch,
  cancel). Razorpay numbers and emails the invoice and hosts the payment page; Core stores references,
  amounts and states (`invoice`, `payment_transaction`, `payment_webhook_event`; migration
  `V202610140900`).
- Renewal invoices are issued automatically 7 days before the period ends (hourly, multi-instance
  safe; at most one open or paid invoice per period, enforced by a unique key). Staff can issue an
  invoice now, cancel it, or re-check it at Razorpay.
- Webhook `POST /api/v1/webhooks/payments/razorpay`: signature check over the raw body, 401 + audit on
  failure, idempotent by event id. A paid invoice activates a trial (keeping its remaining days) or
  renews the period it was issued for, exactly once (S-8).
- Company billing contact (BILLING, else PRIMARY) is the invoice recipient.
- Portal: company Billing tab (invoices, Pay link for `company.billing.manage`, payments; staff can
  check payment or cancel), platform Invoices list, "Issue invoice for next period" on the
  subscription page.
- New error codes `PAYMENT_GATEWAY_ERROR` (502) and `BILLING_DISABLED` (503); billing is off until
  Razorpay keys are configured.
- Tests: BillingIT (7, against a Razorpay stub: issuing, one invoice per period, gateway failure,
  forged/replayed webhooks, trial activation, partial payment, expiry, cancel, refresh, renewal job),
  unit tests for signature and request mapping, 49 frontend tests. Browser walkthrough against the
  Docker stack with a local Razorpay stub.
- Fixed during verification: paying a trial invoice early started the paid month on the payment date
  instead of at the end of the trial.
- Not verified against Razorpay itself (not reachable from the build sandbox) — see risk R-5 and the
  smoke test in DEPLOYMENT.md. GST treatment is open (D-11).

### Phase 8 — Frontend portals (2026-10-13)
- Sign-in: own OpenID Connect client (Authorization Code + PKCE, tokens in memory, refresh,
  logout), auth interceptor, guards, MFA notice for platform staff.
- Super Admin screens: dashboard, companies (list, create), users (list, detail, enable/disable,
  platform roles), products (register, edit, activate/retire, configuration versions with schema
  errors shown, features), plans (editor with prices, products and limits; publish, archive, copy),
  subscriptions, provisioning jobs (retry, cancel), audit log.
- Company workspace (Company Admins and platform staff): overview (details, settings, contacts,
  status history, status changes for staff), product launcher, subscription (assign and lifecycle
  actions for staff), users and invitations (invite, roles, suspend/reactivate/remove, resend/revoke),
  usage and limits, audit log; invitation acceptance page; own profile page.
- Shared UI: page header, status badges, pager, accessible confirmation dialog, server validation
  errors on form fields; responsive tables and navigation.
- Core: `GET /api/v1/me/profile` (the profile page needs current values before saving).
- Portal Nginx: invitation URLs (one-time tokens) are not written to the access log.
- Tests: 47 frontend tests; lint, format and production build clean. Browser walkthrough with
  Playwright against the Docker stack: Super Admin sign-in with TOTP enrolment and OTP, product,
  plan, company, subscription, automatic provisioning of the sample product, company admin
  invitation from the real email through to the company workspace, phone-width layout.
- Fixed during verification: a zoneless change-detection gap in the plan editor, list endpoint
  without platform roles, wording of company status actions (companies start active), hidden
  dialogs adding layout gaps.

### Phase 7 — Provisioning & sample product (2026-10-13)
- Service-to-service sign-in: client-credentials tokens become a service principal limited to the
  new service API; products are linked to their Keycloak client (`serviceClientId`); service actions
  are audited with the client id; separate rate limit for services.
- Service API for products: access check for a company and user (membership, permission, company
  status, entitlement), usage consume/release, provisioning callbacks.
- Provisioning: jobs created with every entitlement change, multi-instance runner (SKIP LOCKED,
  leases, HTTP outside transactions), SYNC/ASYNC/NONE modes, retries with exponential back-off,
  permanent failures with alert email, metric and audit, manual retry/cancel, deprovisioning after
  the 90-day retention period (cancelled if the company is entitled again), product launcher
  `GET /api/v1/companies/{companyId}/products`.
- Sample "Notes" product (`backend/product-sample`): own database, provisioning endpoints for
  Core only, notes API authorised by Core with usage limits; Dockerfile and compose service.
- Keycloak realm: clients `occpl-core-services`, `product-sample-service`, `product-sample-web`.
- `backend/e2e-tests`: Keycloak + Core + sample product process + two databases, end to end.
- Executable jars now use the `exec` classifier; core-platform publishes its test helpers as a test jar.
- Tests: 73 unit/slice + 64 integration tests in Core, 2 sample-product integration tests,
  1 end-to-end test.

### Phase 6 — Plans, subscriptions & entitlements (2026-10-13)
- Plans: drafts with prices per currency, products and feature grants; publish (immutable),
  archive, copy.
- Subscriptions: assign (trial or paid), explicit state machine, activate, renew, upgrade now /
  downgrade at period end, cancel now or at period end, resume, suspend; history and audit;
  automatic sweeps for trial expiry, past due, 7-day grace → suspended, 90-day suspension → expired,
  90-day data retention date.
- Entitlements recomputed with every subscription change; access checks apply company status;
  atomic, idempotent usage counters; seat limits on invitations and reactivation.
- Tests: 69 unit/slice (incl. all 36 state-machine combinations) + 58 integration tests (incl. a
  120-thread concurrency test of usage limits).

### Phase 5 — Product catalogue (2026-10-13)
- Products: register (DRAFT), search, get, PATCH with ETag (code immutable, unique API audience,
  HTTPS-only URLs), activate (requires published configuration) / retire / reinstate, audited.
- Configuration versions: validated against a published JSON Schema (`GET /api/v1/products/config-schema`)
  plus no-secrets and consistency rules; immutable; identical content rejected; publish and rollback;
  draft deletion.
- Features: BOOLEAN and LIMIT (unit, reset period; stock units must not reset), deprecate/reactivate.
- `ProductCatalog` module API for plans and provisioning; multi-field `ValidationException`.
- Tests: 31 unit/slice + 48 integration tests. Fixed an order-dependent integration test.

### Phase 4 — Company & membership management (2026-10-13)
- Companies: create (with settings, primary contact and initial Company Admin invitation in one
  transaction), search, get, PATCH with ETag/If-Match, activate/suspend/deactivate with status
  history, settings (allowed invitation domains) and contacts; all changes audited.
- Invitations: create, list, resend, revoke, accept, scheduled expiry; hashed tokens; delivery via
  Keycloak account-setup email (new users) or the platform invitation email (existing users).
- Members: list/search/filter/sort, detail, suspend/reactivate/deactivate, role replacement with
  no-escalation, no-self-change and last-admin rules.
- Platform users: keyset list, detail, disable/enable synchronised to Keycloak, user memberships.
- Audit log API (platform and company scoped, keyset cursor).
- Notification module: transactional email outbox with SMTP dispatcher, retries and body clearing.
- Docker Compose: Mailpit inbox at http://localhost:8025; Keycloak SMTP for local development.
- Tests: 23 unit/slice + 44 integration tests, including an end-to-end invitation through real
  Keycloak and SMTP. Fixed during verification: comma-separated `sort` parameters were split by Spring.

### Phase 3 — Identity & access (2026-10-13)
- Flyway migrations for users, identities, profiles, companies, memberships, permissions, roles,
  role assignments and the audit log; seeded catalogue of 28 permissions and 5 system roles.
- Identity: JWT → platform user mapping (verified-email linking, takeover protection, disabled and
  unverified accounts rejected with specific error codes), first Super Admin bootstrap, MFA policy
  based on the `amr` claim, platform role API (`/api/v1/platform/roles`,
  `/api/v1/platform/users/{id}/roles`) with last-Super-Admin protection, `PUT /api/v1/me/profile`,
  Keycloak Admin REST client using the `occpl-core-admin` service account.
- Membership: `@tenant.check` company authorization (cross-tenant → 404, suspended company
  read-only), `TenantContext`, `GET /api/v1/me`. Access: `GET /api/v1/permissions`.
- Audit module (`AuditLogger`, same-transaction writes). Redis rate limiting (Bucket4j, fail-open).
- Keycloak realm: service-account client, AMR mapper, `occpl browser` flow with authentication
  references; Swagger UI sign-in through Keycloak for development.
- Docker Compose: Redis, Keycloak admin secret, bootstrap admin, Swagger OAuth.
- Tests: 23 unit/slice + 26 integration tests (MySQL 9.7, Keycloak 26.8.1, Redis 8.10), including
  tenant isolation and MFA enrolment through Keycloak's login pages.
- Fixed during verification: native-query UUID projection, Swagger UI blocked by the API CSP.

### Phase 2 — Foundation (2026-10-13)
- Versions: Java 25 LTS, Spring Boot 4.1.1, Angular 22.2, MySQL 9.7 LTS, Keycloak 26.8.1 (ADR-0007).
- `backend/core-platform`: Spring Boot modular monolith skeleton with the `shared` module
  (RFC 9457 error handling, correlation ids, UUIDv7, paging, UTC clock), stateless OAuth2
  resource server with issuer/audience validation, CORS allowlist, security headers, Actuator on a
  separate port, Prometheus metrics, OpenAPI, structured logging, Flyway, Spring Modulith
  verification. 22 unit/slice tests + 5 Testcontainers integration tests (MySQL 9.7).
- `frontend/admin-portal`: Angular 22 app with runtime configuration, correlation-id and API-error
  interceptors, shell layout with accessible error alert, design tokens (light/dark), lazy routes,
  ESLint + Prettier. 14 Vitest tests.
- `infra/docker`: Compose stack (MySQL with least-privilege users, Keycloak realm import, core,
  portal); Dockerfiles for both apps (non-root runtime images).
- CI: GitHub Actions workflow and Dependabot configuration.
- Docs: decisions D-1–D-4 recorded, ADRs accepted, ADR-0007 version policy, risks section.

### Phase 1 — Discovery & architecture

- Phase 1 discovery & architecture documentation: PROJECT.md, ARCHITECTURE.md, DATABASE.md,
  API_SPEC.md, SECURITY.md, BACKLOG.md, DEPLOYMENT.md, MEMORY/AI_CONTEXT.md and ADR-0001…0006.
  Documentation only; no application code yet.
