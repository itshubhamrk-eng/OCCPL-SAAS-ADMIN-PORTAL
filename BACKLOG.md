# Backlog

Status legend: ☐ not started · ◐ in progress · ☑ done (implementation **and** tests verified).
Priority follows the phase order in the master prompt. Each item lists acceptance criteria (AC).

## Phase 1 — Discovery & architecture

- ☑ **P1-1** Phase 1 documentation set. Decisions D-1–D-4 answered on 2026-10-13; D-5 deferred
  to Phase 6; D-6–D-10 open with defaults.

## Phase 2 — Foundation

- ☑ **F-1** Repo layout, Maven aggregator, `core-platform` (Java 25, Spring Boot 4.1.1), Angular 22
  workspace (strict TS + strict templates). Verified: `mvn verify` green; `ng build`, `ng test`,
  `ng lint`, `prettier --check` green.
- ☑ **F-2** Docker Compose: MySQL 9.7 (least-privilege users), Keycloak 26.8.1 (realm import),
  core, portal (unprivileged Nginx, runtime config, strict CSP). Verified in this environment with
  images built from host artifacts: all services healthy, JWT validation end-to-end, portal renders
  with no console/CSP errors. *Not verified here:* the in-container `mvn`/`npm` build stages (the
  sandbox has no network inside `docker build`); CI builds both images. Redis and Mailpit are added
  when first needed (Phase 3 / notifications).
- ☑ **F-3** `shared` module: UUIDv7, UTC Clock, paging, RFC 9457 error handling, correlation-id
  filter, structured JSON logging. No Flyway migrations yet (first tables in Phase 3); Flyway +
  `ddl-auto=validate` verified against MySQL 9.7 by `CorePlatformApplicationIT`.
- ☑ **F-4** Module-boundary verification with Spring Modulith (`ModularityTests`). ArchUnit
  dropped as redundant. Rule violation tests become meaningful once a second module exists (Phase 3).
- ☑ **F-5** Actuator liveness/readiness/info/prometheus on management port 8081 (others denied),
  Hikari metrics, OpenAPI (off unless `API_DOCS_ENABLED=true`). Verified by integration tests.
- ☑ **F-6** GitHub Actions CI (backend verify, frontend checks + `npm audit`, image builds) and
  Dependabot. *Not yet run on GitHub* — first run happens on the next PR/push to `main`.

## Phase 3 — Identity & access

- ◐ **I-1** Resource-server JWT validation against Keycloak. Verified by `KeycloakIdentityIT` with
  real Keycloak tokens: valid token accepted; token without the `occpl-core` audience and token from
  another realm (issuer) → 401. *Remaining:* a dedicated expired-token test (expiry is enforced by
  Spring Security's default timestamp validator, 60 s skew, but not covered by our own test).
- ☑ **I-2** User mapping (`platform_user`, `user_identity`, `user_profile`), `GET /me`,
  `PUT /me/profile`. Verified (`TenantIsolationIT`): verified email creates or links (invited user
  activated); unverified → 403 `EMAIL_NOT_VERIFIED`; email already linked to another subject →
  403 `IDENTITY_CONFLICT`; disabled → 403 `ACCOUNT_DISABLED`; 6 concurrent first logins → 1 user.
- ☑ **I-3** Permission catalogue (28) and system roles seeded; platform authorities;
  `@tenant.check(...)` company authorization with `TenantContext`; first Super Admin bootstrap;
  platform role API with last-Super-Admin protection and audit; MFA required for platform
  permissions. Verified: 15 tenant-isolation tests (cross-tenant 404 indistinguishable from
  missing, 403 outside role, suspended/deactivated states, platform staff with/without MFA) and the
  full MFA enrolment flow through Keycloak's login pages (`KeycloakIdentityIT`).
- ☑ **I-4** Keycloak admin client (create user, find, required actions, credentials, enable/disable,
  logout, actions email) using the `occpl-core-admin` service account. Verified against Keycloak
  26.8.1 except `sendActionsEmail`, which needs SMTP (exercised with invitations in Phase 4).
- ☑ **I-5** Redis rate limiting (Bucket4j): per user and per IP, 429 + `Retry-After` +
  `RATE_LIMITED`; fails open when Redis is down (`occpl.ratelimit.errors` metric). Verified by
  `RateLimitIT` (real Redis) and `RedisRateLimiterTest`.
- ☑ **I-6** Swagger UI sign-in through Keycloak for non-production environments; separate CSP for
  documentation pages. Verified in the Docker stack with headless Chromium (no console errors).

## Phase 4 — Company & membership management

- ☑ **C-1** Companies: create (with primary contact and initial Company Admin invitation in one
  transaction), search (code/name prefix, status, country, sort allowlist, paging), get, PATCH with
  ETag/If-Match (company admins: display name only), activate/suspend/deactivate with reasons,
  status history and audit. Verified (`CompanyAndMembershipIT`): validation 400, duplicate code 409,
  invalid transition 422, stale If-Match 412, field restrictions 403, audit rows.
- ☑ **C-2** Invitations: create, list, resend (new token, old one invalid), revoke, accept, expiry
  job; one pending invitation per membership (DB unique key); token stored as SHA-256 only, sent in
  the request body; expired → 410. Delivery: new users get Keycloak's account-setup email (verifies
  the address) that returns to the accept link; existing users get the platform invitation email.
  Verified end to end with real Keycloak and SMTP (`KeycloakInvitationIT`) and with mocks
  (wrong user → 404, idempotent repeat, expired, resent, revoked).
- ☑ **C-3** Members: list/search/filter/sort, detail, suspend/reactivate/deactivate, replace roles.
  Rules: no escalation beyond the actor's own permissions, no self-changes, last active Company
  Admin protected (row locks). Verified by negative tests for each rule.
- ☑ **C-4** Company settings (time zone, locale, allowed invitation email domains) and contacts
  (PRIMARY/BILLING/TECHNICAL; primary cannot be removed), ETag on settings.
- ☑ **C-5** Platform users: keyset list with email prefix search, detail with platform roles,
  disable/enable synchronised to Keycloak (account disabled + sessions ended), user's memberships.
- ☑ **C-6** Audit log API: platform search and company-scoped trail, keyset cursor, filters.
- ☑ **C-7** Notification module: transactional email outbox, SMTP dispatcher (`SKIP LOCKED`, back-off,
  max attempts, bodies cleared after sending). Verified with GreenMail (`NotificationDispatcherIT`).
- ☐ **C-8** Custom company roles (`/companies/{id}/roles` POST/PUT/DELETE, `company.roles.manage`).
  Data model and assignment already support company-specific roles.
- ☐ **C-9** Branded Keycloak email theme (the account-setup email currently uses Keycloak's default text).
- ☐ **C-10** `Idempotency-Key` support for POST endpoints (duplicates are already rejected by unique keys).
- ☑ **C-11** Seat limits on invitations — done in Phase 6 (see there; hardened in Phase 9).

## Phase 5 — Product catalogue

- ☑ **PC-1** Products: register (DRAFT; `apiAudience` defaults to `product-<code>` and is unique),
  search, get, PATCH with ETag/If-Match (`code` immutable), activate (requires a published
  configuration; also reinstates), retire. URLs must be HTTPS (HTTP only for localhost), without
  credentials, query or fragment. Verified (`CatalogIT`, `UrlRulesTest`). The rule "retired products
  cannot be added to new plans" is exposed through `ProductCatalog` and enforced when plans exist (Phase 6).
- ☑ **PC-2** Configuration versions validated against a published JSON Schema
  (`GET /products/config-schema`) plus rules: no secrets (names or values), unique setting keys,
  consistent defaults/options. Versions are immutable; identical content (any key order) → 409;
  publish makes a version current; re-publishing an older version is a rollback; only drafts can be
  deleted. Verified (`ProductConfigValidatorTest`, `CatalogIT`).
- ☑ **PC-3** Features: BOOLEAN or LIMIT (unit + reset period; stock units such as SEATS must use
  NONE), immutable code/type/unit/period, rename, deprecate/reactivate, duplicate code → 409.
  Verified (`CatalogIT`).
- ☐ **PC-4** Per-company values for `tenantSettings` (definitions exist; values are set when products
  are provisioned, Phase 7).
- ☐ **PC-5** Product logo/branding assets (needs safe file upload handling).

## Phase 6 — Plans, subscriptions & entitlements

- ☑ **S-1** Plans: draft with prices per ISO 4217 currency, products and feature grants (on/off,
  limits; omitted limit = unlimited), validated against the catalogue; publish requires a price and
  active products/features; ACTIVE plans are immutable (copy to change); archive. Verified (`SubscriptionsAndEntitlementsIT`).
- ☑ **S-2** Subscriptions: assign (trial or first paid period; one live subscription per company and
  plan), explicit state machine with history and audit. Verified: `SubscriptionStateMachineTest`
  covers all 36 status/event combinations plus terminal states.
- ☑ **S-3** Upgrade (price ≥ current) immediate; downgrade scheduled and applied at renewal; renew
  (ACTIVE/PAST_DUE continue from the old period end, SUSPENDED restarts now); cancel immediately or at
  period end; resume. Verified, including the full history sequence.
- ☑ **S-4** Entitlements recomputed in the same transaction as every subscription change (best
  status ACTIVE > GRACE > SUSPENDED; largest limit wins, unlimited beats any number); access checks
  apply company status. *Changed from plan:* no Redis cache — decisions read the database each time,
  so changes apply immediately; a cache will be added only if load tests require it.
- ☑ **S-5** Usage consume/release: limit enforced in one conditional UPDATE, idempotency key unique
  per company, releases never go below zero. Verified: 120 concurrent consumers against a limit of
  50 → exactly 50 succeed and 50 usage records; replayed keys are not counted twice.
- ☑ **S-6** Scheduled sweeps every 5 minutes: trial expiry, period end → PAST_DUE (or EXPIRED if
  cancelling), PAST_DUE beyond the 7-day grace → SUSPENDED, SUSPENDED for 90 days → EXPIRED; data kept
  90 days after cancellation/expiry (`dataRetentionUntil`). *Changed from plan:* no ShedLock — each
  subscription is handled under a row lock and re-checked, so several instances can run safely.
  Verified, including idempotent re-runs.
- ☑ **C-11** Seat limits: ACTIVE + INVITED members may not exceed the largest SEATS limit of the
  company's accessible products; enforced on invitations and reactivation. Verified.
- ☑ **S-7** Service-facing entitlement check and usage endpoints for product services — done in
  Phase 7 (`/api/v1/service/access`, `/api/v1/service/usage/*`).
- ☑ **S-8** Payment-driven renewal and invoices — done in the billing phase: a paid invoice activates a
  trial (the rest of the trial is kept) or renews the period it was issued for. Manual renewal stays available to staff.
- ☐ **S-9** Per-product user assignment (seats per product instead of company-wide members).

## Phase 7 — Provisioning & sample product

- ☑ **PR-1** Integration contract: specified in ARCHITECTURE.md §7 and API_SPEC.md; the service API is
  part of Core's OpenAPI document (tag "Service API"). Decision: no shared DTO module — products
  share no code with Core and integrate over HTTP only.
- ☑ **PR-2** Provisioning job runner: jobs created in the subscription change's transaction, SKIP
  LOCKED claiming with leases, HTTP outside transactions, exponential back-off, max attempts, manual
  retry/cancel, ASYNC callbacks with deadline, delayed deprovisioning (D-5 retention) with
  cancellation on re-entitlement. Tests: timeout, 5xx/429 retry, 4xx permanent failure, callback
  ownership and repetition, callback deadline, idempotent product endpoints.
- ☑ **PR-3** `product-sample` (Notes): JWT validation (audience `product-sample`), provisioning only
  from Core's client, idempotent PUT/DELETE, access verified with Core on every request, usage
  consume/release, own database and migrations. End-to-end test `backend/e2e-tests` (Keycloak +
  Core + sample product process + two databases).
- ☑ **PR-4** Permanent provisioning failures: error log, `occpl.provisioning.jobs` metric
  (outcome/operation/product), email to `PROVISIONING_ALERT_RECIPIENTS`, audit entry.
- ☐ **PR-5** Product UPDATE operation (re-send configuration/settings to provisioned tenants when a
  new configuration version is published) — not needed by the sample product.
- ☐ **PR-6** Short-lived cache (≤ 30 s) of access checks in product services if load tests require it.
- ☑ **PR-7** Alert on `occpl_provisioning_jobs_total{outcome="failed"}`: CloudWatch alarm `provisioning-failed` (Phase 10).

## Phase 8 — Frontend portals

- ☑ **UI-1** Shell, OIDC login/logout (own PKCE client, tokens in memory), interceptors, guards, design tokens,
  company navigation, MFA notice — done in Phase 8.
- ☑ **UI-2** (done, Phase 8; platform settings page deferred: no settings API yet) Super Admin: dashboard, companies, users, products, plans, subscriptions,
  entitlements, provisioning, audit, settings.
- ☑ **UI-3** (done, Phase 8; billing history waits for the billing module, custom roles for C-8) Company Admin: dashboard, profile/settings, products & launch, subscription,
  users, roles, usage, billing history.
- AC (all): typed API clients, reactive forms with validation messages, responsive at 360 px and
  desktop, keyboard accessible, component/service unit tests.

- ◐ **UI-4** Billing history page — done (company Billing tab, platform Invoices list). Platform settings page still needs a settings API.
- ☐ **UI-5** Show company and actor names instead of ids in the platform subscription list and audit
  log (needs batch name lookups or names in those API responses).
- ☐ **UI-6** Browser end-to-end tests in CI (the Phase 8 walkthrough ran manually with Playwright
  against the Docker stack).

## Billing (done 2026-10-14)

- ☑ **B-1** `PaymentGateway` port + `RazorpayGateway` adapter (Invoices API: create, fetch, cancel);
  invoices and payment transactions; one open invoice per subscription period (database constraint);
  hourly job issues renewal invoices 7 days before the period ends (multi-instance safe); staff can
  issue, cancel and re-check invoices; billing disabled until keys are configured. Tests: BillingIT
  (gateway stub), unit tests; browser walkthrough against the Docker stack with a local stub.
- ☑ **B-2** `POST /api/v1/webhooks/payments/razorpay`: HMAC-SHA256 signature over the raw body,
  idempotency by `x-razorpay-event-id`, invoice.paid/partially_paid/expired, payment.captured/failed.
  AC met: replayed webhook → DUPLICATE, no side effects; bad signature → 401 and audit entry.
- ☐ **B-3** Live verification with Razorpay test-mode keys and a real webhook (risk R-5; not possible
  from the build sandbox).
- ☐ **B-4** GST on invoices once D-11 is decided (tax fields, GSTIN, HSN/SAC).
- ☐ **B-5** Separate platform billing permissions (`platform.billing.read/manage`); billing currently
  reuses `platform.subscriptions.*`.
- ☐ **B-6** Alert for invoices stuck in ISSUE_FAILED (e.g. no billing contact) and a "retry issue" action;
  today the job retries on its next run and the failure is in the audit log.
- ☐ **B-7** Refunds and credit notes (Razorpay refunds API) — not in scope yet.
- ☐ **B-8** Company Admin self-serve checkout (depends on D-7).

## Phase 9 — Hardening

- ☑ **H-1** Security review (independent code review: 8 findings, all fixed with tests), Trivy
  dependency/image scan (critical Tomcat and high Jackson CVEs fixed; 0 known in the Java libraries
  afterwards), Trivy in CI, Dependabot for all modules, header/CORS verification with curl (portal
  header inheritance bug fixed). Details: SECURITY.md "Implemented in Phase 9".
- ☑ **H-2** `EndpointSecurityIT` derived from the request mappings (authorization declared, 401
  anonymous, user/service token separation, 404 for every endpoint of another company with any body,
  foreign nested ids not found) and `CompanyPathGuard` (deny-by-default before body parsing).
- ☑ **H-3** k6 load tests on 10k companies / 1M users (Zipf sizes): all provisional SLOs met up to
  210 req/s on the 4-vCPU development VM (everything incl. the generator on one machine); member
  search fixed (507 → 54 ms p95); overload now answers 503 + Retry-After. `load-tests/RESULTS.md`.
- ☑ **H-4** Backup/restore drill with MySQL Shell dumps + binary-log increments: full backup 5 s,
  increments 1–2 s every 5 min (RPO ≤ 5 min), restore 41–43 s + Core 14 s (RTO ≈ 1 min at this size),
  exact point-in-time recovery before an accidental DELETE. `infra/backup/DRILL.md`.
- ☐ **H-5** Repeat H-3 on the production environment before launch (no AWS staging, D-15): both
  Core instances behind the load balancer, RDS, generator on its own machine; soak test (hours); Keycloak login throughput.
  Needed before confirming D-6 and sizing production.
- ☐ **H-6** Usage-counter throughput under contention on one company (correctness is covered).
- ☐ **H-7** Personal-data export and erasure (D-10, DPDP Act) — decision still open.

## Phase 10 — Deployment

- ◐ **D-1** AWS deployment (ADR-0011), code complete; not yet applied to an AWS account:
  - ☑ **D-1a** Terraform: state bucket; shared stack (ECR, artifacts bucket, GitHub OIDC, SES with
    DKIM/MAIL FROM/DMARC); environment module (VPC in two AZs, ALB + ACM + WAF, EC2 Auto Scaling
    group with SSM, RDS MySQL 8.4, ElastiCache Valkey with TLS, Secrets Manager, IAM); production
    stack (no AWS staging, D-15). `terraform validate` passes for all stacks.
  - ☑ **D-1b** Production compose bundle, production Keycloak image (optimized MySQL build, realm
    import generated from the development realm), instance scripts (render config from SSM and
    Secrets Manager, deploy, database bootstrap). Rehearsed locally end to end (MySQL 8.4, nginx as
    the ALB, browser login with MFA through the public hostnames).
  - ☑ **D-1c** CI/CD: images to ECR per commit, approval-gated rolling deploy to production, smoke test; manual redeploy/rollback; automatic rollback of all updated instances on failure
    (rehearsed with a stubbed AWS CLI). CI infra job (fmt, validate, realm check, shellcheck).
  - ☑ **D-1d** CloudWatch logs, metrics and alarms → SNS email; RUNBOOK.md (setup, deploy,
    rollback, recovery, incidents, secret rotation, maintenance).
  - ☐ **D-1e** First apply on the AWS account and first deploy to production before launch, with a
    rollback drill (deploy the previous release) while there are no customers yet. Needs: AWS account access, the Route 53 zone for occplsoftech.com,
    SES production access, GitHub environments/variables (RUNBOOK.md §1).
- ◐ **D-2** Backups in production: RDS point-in-time recovery (35 days) + AWS Backup daily copy
  to ap-south-2, failure alerts, Keycloak database included — done in Terraform. ☐ Restore drill on
  RDS (point-in-time restore into a new instance, measure RTO) after D-1e.
- ☑ **D-3** Audit log append-only for every account (database triggers, `V202610141100`).
- ☐ **D-4** Metrics for outbox lag (oldest unsent notification / provisioning job) and the
  subscription sweep's last run, with alarms.
- ☐ **D-5** Redis TLS path verified against ElastiCache in production before launch (rehearsal had no TLS Redis;
  Core fails open, and the `rate-limiter-down` alarm covers it).
- ☐ **D-6** Keycloak realm changes after the first import: scripted with `kcadm.sh`, or a
  config-as-code tool, instead of manual console changes.
