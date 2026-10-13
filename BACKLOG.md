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
- ☐ **C-11** Seat limits on invitations (needs entitlements, Phase 6).

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

- ☐ **S-1** Plans, prices, plan products/features, publish/archive. AC: ACTIVE plans immutable.
- ☐ **S-2** Subscription assignment + state machine + history. AC: table-driven tests cover every
  allowed and forbidden transition in DATABASE.md §5.
- ☐ **S-3** Upgrade/downgrade/cancel/renew rules. AC: upgrade immediate, downgrade scheduled,
  history recorded.
- ☐ **S-4** Entitlement recompute (event-driven) + check API + cache with eviction. AC: max-of
  limits across subscriptions; suspended company denies access within TTL.
- ☐ **S-5** Usage consume/release with atomic limits and idempotency. AC: concurrent test (N
  threads) never exceeds limit; replayed key does not double count.
- ☐ **S-6** Scheduled sweeps (trial expiry, renewals, grace → suspend, expiry) with ShedLock.
  AC: idempotent re-runs; only one instance executes.

## Phase 7 — Provisioning & sample product

- ☐ **PR-1** Integration contract OpenAPI (`/backend/integration-contract`).
- ☐ **PR-2** Outbox + provisioning job runner with backoff, max attempts, manual retry.
  AC: tests for timeout, 5xx retry, 4xx permanent failure, duplicate delivery idempotent.
- ☐ **PR-3** `product-sample` service: JWT validation, tenant verification via Core, idempotent
  provisioning endpoint, own DB. AC: end-to-end test Core ↔ sample product in Testcontainers.
- ☐ **PR-4** Provisioning failure notifications + metrics/alerts.

## Phase 8 — Frontend portals

- ☐ **UI-1** Shell, OIDC login/logout, interceptors, guards, design tokens, company switcher.
- ☐ **UI-2** Super Admin: dashboard, companies, users, products, plans, subscriptions,
  entitlements, provisioning, audit, settings.
- ☐ **UI-3** Company Admin: dashboard, profile/settings, products & launch, subscription,
  users, roles, usage, billing history.
- AC (all): typed API clients, reactive forms with validation messages, responsive at 360 px and
  desktop, keyboard accessible, component/service unit tests.

## Billing (scheduled after D-3/D-4, can run parallel to Phase 8)

- ☐ **B-1** Gateway port + adapter, invoices, payment transactions.
- ☐ **B-2** Webhook endpoint: signature verification, idempotency by event id, state updates.
  AC: replayed webhook has no side effects; bad signature → 401 and audit.

## Phase 9 — Hardening

- ☐ **H-1** Security review, OWASP dependency scan, header/CORS verification.
- ☐ **H-2** Tenant-isolation test suite across all company-scoped endpoints.
- ☐ **H-3** Load tests (k6): seed 10k companies / 1M users distribution (power-law: most
  companies < 50 users, few > 5k); scenarios: login + `/me`, company-scoped listings,
  entitlement checks, usage consume, admin writes, platform listings. AC: meet SLOs in
  PROJECT.md §2 at agreed RPS; results recorded in `load-tests/RESULTS.md` (measured, never
  assumed).
- ☐ **H-4** Backup/restore drill with measured RPO/RTO.

## Phase 10 — Deployment

- ☐ **D-1** Production compose/VM deployment, Nginx TLS, secrets injection, monitoring stack,
  runbook, rollback procedure verified on staging.
