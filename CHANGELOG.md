# Changelog

All notable changes to this project are documented here.

## [Unreleased]

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
