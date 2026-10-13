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

- ◐ **I-1** Resource-server JWT validation (iss/aud/exp) against Keycloak. Configured in Phase 2
  and checked manually end-to-end; remaining AC: automated tests with signed tokens for invalid
  issuer/audience/expired token → 401.
- ☐ **I-2** User mapping (`user`, `user_identity`), `GET /me`. AC: first authenticated call
  links invited user by verified email; unverified email not linked.
- ☐ **I-3** Permissions/roles seed + `access` module decision service + `TenantContext`
  resolution. AC: unit tests for decision matrix; integration test that a user without
  membership gets 404 on company routes.
- ☐ **I-4** Keycloak admin client (create user, required actions, disable). AC: Testcontainers
  Keycloak integration test.
- ☐ **I-5** Rate limiting (Redis bucket). AC: 429 + `Retry-After` after threshold in test.

## Phase 4 — Company & membership management

- ☐ **C-1** Company CRUD + lifecycle + search/pagination + audit. AC: validation, duplicate
  code → 409, invalid transition → 422, audit rows written.
- ☐ **C-2** Invitations (create, resend, revoke, accept, expiry job). AC: one pending invitation
  per (company, email); token hashed; expired token rejected.
- ☐ **C-3** Membership lifecycle + role assignment (no escalation, last-admin rule). AC: negative
  tests for escalation and last-admin removal.
- ☐ **C-4** Company settings & contacts. AC: Company Admin can edit allowed fields only.

## Phase 5 — Product catalogue

- ☐ **PC-1** Product register/update/activate/retire. AC: immutable `code`; retired product
  cannot be added to new plans.
- ☐ **PC-2** Versioned, JSON-schema-validated configuration. AC: invalid config → 400; published
  versions immutable.
- ☐ **PC-3** Product features (BOOLEAN/LIMIT, unit, reset period). AC: duplicate feature code → 409.

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
