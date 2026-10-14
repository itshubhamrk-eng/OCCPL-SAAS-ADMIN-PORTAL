# Architecture

Status: Phase 1 design; Phase 2 foundation implemented (`shared` module, security baseline,
observability, local stack). Decisions are recorded in `ADR/`.

## 1. System context

```
                 ┌──────────────────────────── Browser ─────────────────────────────┐
                 │  Angular app (Super Admin portal + Company Admin portal)          │
                 └───────────────┬───────────────────────────────┬──────────────────┘
                     OIDC (PKCE) │                               │ HTTPS + Bearer token
                                 ▼                               ▼
                       ┌──────────────────┐   JWKS     ┌──────────────────────────┐
                       │ Keycloak (IdP)   │◄───────────│ Nginx (TLS, routing,     │
                       │ realm: occpl     │            │ headers, coarse limits)  │
                       └────────┬─────────┘            └────────────┬─────────────┘
                     Admin REST │ (invite/disable users)            ▼
                                │                    ┌──────────────────────────────┐
                                └───────────────────►│ Core Platform (Spring Boot,  │
                                                     │ modular monolith, N replicas)│
                                                     └──┬──────────┬──────────┬─────┘
                                                        │          │          │ REST (service token,
                                                  MySQL 9.7│    Redis │          │ idempotency keys)
                                                        ▼          ▼          ▼
                                                    core DB    cache/RL   ┌───────────────┐
                                                                          │ Product A/B/C │──► own DB
                                                                          │ services      │
                                                                          └───────┬───────┘
                                                Payment gateway ──webhooks──► Core │
                                                       Product services validate user JWTs via
                                                       the same IdP and call Core entitlement API
```

## 2. Deployable units

| Unit | Responsibility | Data |
|---|---|---|
| `core-platform` (Spring Boot) | All platform modules below | `occpl_core` MySQL schema |
| `admin-portal` (Angular, served by Nginx) | Super Admin + Company Admin UIs | none |
| `keycloak` | Authentication, token issuance, MFA, password policy | own DB (Postgres or MySQL) |
| `product-sample` (Spring Boot) | Reference "Notes" product implementing the integration contract (Phase 7, implemented) | own database `occpl_sample` |
| Nginx | TLS termination, static assets, reverse proxy, security headers | — |
| Redis | Entitlement cache, rate-limit buckets (added in Phase 3, ADR-0006) | ephemeral |

## 3. Repository layout (proposed for Phase 2)

```
/backend/core-platform        Maven project (Spring Boot)
/backend/product-sample       Maven project: sample product service (Phase 7)
/backend/e2e-tests            End-to-end tests: Keycloak + Core + sample product (Phase 7)
/frontend/admin-portal        Angular workspace
/infra/docker                 docker-compose.yml, Keycloak realm import, MySQL user bootstrap
/.github                      CI workflow (GitHub Actions) and Dependabot
/load-tests                   k6 scripts
/ADR, /MEMORY, *.md           documentation
```

A Maven multi-module parent (`/backend/pom.xml`) keeps versions aligned while keeping the
product service independently buildable and deployable.

## 4. Core Platform modules and dependency rules

Package root: `com.occpl.platform`. Package-by-module; each module has:

```
<module>/
  api/        public facade interfaces, DTOs, domain events  ← only package other modules may use
  web/        REST controllers (module-private)
  internal/   domain model, JPA entities, repositories, services (module-private)
```

| # | Module | Owns | May depend on (`api` only) |
|---|---|---|---|
| 0 | `shared` | error model, paging, ids, clock, correlation id, security chain, rate limiting, `Actor`, `ServicePrincipal`, `JwtPrincipalResolver` | — |
| 1 | `identity` | users, IdP subject mapping, profiles, `CurrentUser` / `ServicePrincipal` resolution, MFA policy, Super Admin bootstrap, platform role assignment API, Keycloak admin client | shared, access, audit |
| 2 | `company` | company, settings (allowed email domains), contacts, lifecycle + history; publishes `CompanyCreated` | shared, identity, audit |
| 3 | `membership` | memberships, invitations (delivery via Keycloak or email), `@tenant` company authorization, `TenantContext`, `MembershipDirectory`, `GET /me` | shared, identity, company, access, audit, notification, entitlement (seat limit) |
| 4 | `access` | permission catalogue, roles, platform and membership role assignments, permission resolution | shared |
| 5 | `catalog` | products, immutable configuration versions (JSON Schema validated), features; `ProductCatalog` API | shared, identity, audit |
| 6 | `subscription` | plans, company subscriptions, explicit state machine, history, timed sweeps; publishes `SubscriptionChanged`, exposes `SubscriptionGrants` | shared, identity, company, catalog, audit |
| 7 | `entitlement` | entitlements recomputed on `SubscriptionChanged`, access checks, atomic usage counters, seat limit (`Entitlements` API); publishes `EntitlementsChanged` | shared, catalog, company, subscription |
| 8 | `provisioning` | provisioning jobs and runner, company provisioning state, product-service HTTP client, alerts, product launcher (`ProvisioningCallbacks` API) | shared, catalog, company, entitlement, audit, notification |
| 8a | `integration` | service API for product services (`/api/v1/service/**`): access checks, usage, provisioning callbacks | shared, catalog, entitlement, identity, membership, provisioning |
| 9 | `billing` | invoices, payment transactions, `PaymentGateway` port with the Razorpay adapter, webhooks, renewal invoicing job; applies payments through `SubscriptionBilling` (implemented 2026-10-14) | shared, subscription, company (billing contact), audit |
| 10 | `audit` | append-only audit log | shared |
| 11 | `notification` | templates, outbound email, delivery records | shared |
| 12 | `reporting` | read-only aggregates/dashboards | shared + read APIs of others |

Rules (enforced by Spring Modulith `ApplicationModules.verify()` in `ModularityTests`, run by CI).
Each module's `api` package is declared with `@NamedInterface("api")`; every other sub-package is
internal to the module. `shared` is an OPEN module used by all others (`@Modulithic(sharedModules = "shared")`):

1. A module may reference only another module's `api` package. No access to `internal`/`web`.
2. No cyclic module dependencies. Where a reverse dependency is needed, the downstream module
   listens to a **domain event** instead (e.g. `subscription` publishes
   `SubscriptionActivated`; `entitlement` recalculates; `provisioning` reacts to
   `EntitlementGranted`).
3. A module's tables are written only by that module. Cross-module reads go through `api`
   facades, never shared repositories or cross-module JPA associations. Cross-module references
   are stored as UUID columns (FKs allowed in the database, no `@ManyToOne` across modules).
4. `audit` and `notification` are leaf modules; everything may call them, they call nothing.
   *Documented exception:* read-only listing/search SQL may join another module's tables when an
   API call would need unbounded id fan-out (`MembershipQueries`: member search by email/name and
   the last-admin count). Such queries never write foreign tables and must be replaced by an API or
   read model if a module is extracted.
5. Controllers never return entities; mapping happens in `web` (MapStruct or hand-written).
6. Product-specific logic is forbidden in Core. Product variability lives in validated
   configuration (catalog) and in the product service.

These rules keep each module extractable into a service later: its API becomes a REST/event
contract and its tables move with it.

## 5. Request pipeline (Core Platform)

1. Nginx: TLS, security headers, coarse per-IP rate limit, forwards `X-Request-Id`.
2. `CorrelationIdFilter`: accept/generate correlation id, put in MDC and response header.
3. Spring Security resource server: validate JWT signature (JWKS), `iss`, `aud`, `exp`, `nbf`.
4. `JwtPrincipalResolver` (identity module): map `(provider, sub)` → platform user (first login:
   link/create by verified email), reject disabled users, load platform roles/permissions from the
   database, withhold platform permissions unless the token's `amr` proves MFA → `CurrentUser`.
   Client-credentials tokens (Keycloak adds `client_id` only to service-account tokens) become a
   `ServicePrincipal` with the single authority `SERVICE`: such tokens may call `/api/v1/service/**`
   only, and user tokens may never call it. The integration module maps the client to the product
   whose `serviceClientId` matches (unknown/retired → 403).
5. `RateLimitFilter` (Redis token bucket) keyed by user id, service client id, or client IP when unauthenticated.
6. Method security. Platform: `@PreAuthorize("hasAuthority('platform.roles.manage')")`. Company:
   `@PreAuthorize("@tenant.check(#companyId, 'company.users.read', 'platform.companies.read')")` loads
   the caller's ACTIVE membership in the path's company (404 if none), checks company status and
   permission, and stores `TenantContext` (companyId, membershipId, permissions) for the request.
   Services additionally scope every repository query by `companyId`.
8. Global exception handler → RFC 9457 problem details (see API_SPEC.md §2).

## 6. Asynchronous processing

- **Same-transaction events**: domain events (`SubscriptionChanged` → `EntitlementsChanged`) are
  handled synchronously in the publishing transaction; their durable effects are rows (entitlements,
  `provisioning_job`, `notification`) that commit or roll back with the business change. This is the
  transactional-outbox guarantee without a generic `outbox_event` table, which is not needed while
  all consumers are in-process.
- **Provisioning jobs** (implemented, Phase 7): `ProvisioningRunner` claims due jobs with
  `FOR UPDATE SKIP LOCKED` and a lease (`locked_until` = HTTP timeout + 30 s), commits, calls the
  product **outside** any transaction, then records the outcome under a row lock only if the job is
  still in the claimed attempt. Expired leases (crashed instance) are reclaimed; product endpoints are
  idempotent on the job id. Retryable failures (network, timeout, 5xx, 408, 429, 401) back off
  exponentially (30 s doubling, max 1 h) up to the product's `maxAttempts`; 4xx fail at once. A
  permanent failure sets FAILED, raises an alert (error log, `occpl.provisioning.jobs{outcome=failed}`
  metric, email to `PROVISIONING_ALERT_RECIPIENTS`) and can be retried by platform staff.
- **Billing** (implemented 2026-10-14): `InvoicingJob` (hourly) issues the next-period invoice for
  paid TRIAL/ACTIVE/PAST_DUE subscriptions whose period ends within `BILLING_INVOICE_LEAD_TIME` (7 d)
  and are not cancelling. The invoice row is committed as CREATING, Razorpay is called outside any
  transaction, and the result is recorded afterwards; the unique `open_key` makes concurrent runs on
  several instances create at most one invoice per period. Razorpay emails the invoice and hosts the
  payment page. Its webhooks (signed, deduplicated by event id) mark the invoice paid, and
  `SubscriptionBilling.applyPayment` activates a trial (keeping the remaining trial days: the paid
  month starts at the invoice's period start) or renews the period — once, even if both the webhook
  and a manual "check payment" report the same payment. An unpaid invoice expires after
  `BILLING_PAYMENT_WINDOW` (14 d); the subscription itself follows the D-5 lifecycle (PAST_DUE →
  grace → SUSPENDED) independently of invoices.
- Scheduled jobs: subscription renewals/expiry sweeps, trial expiry, PAST_DUE → SUSPENDED after
  grace, usage-counter period resets, invitation expiry. All idempotent and batch-paginated.
- No Kafka/RabbitMQ initially (ADR-0004). Revisit if outbox throughput or fan-out to many
  product services becomes a measured bottleneck.

## 7. Product integration contract (implemented in Phase 7)

Reference implementation: `backend/product-sample`; verified end to end by `backend/e2e-tests`.

| Concern | Contract |
|---|---|
| Service identity | Two confidential Keycloak clients per direction. **Core → product**: Core's client `occpl-core-services` (client credentials); each product adds an audience mapper for its `apiAudience` to that client, and the product accepts provisioning calls only from tokens whose `client_id` is `occpl-core-services`. **Product → Core**: the product's own client (e.g. `product-sample-service`) with an audience mapper for `occpl-core`; registered in the catalogue as the product's `serviceClientId`. Core derives the calling product from the token's `client_id`, never from the request. mTLS optional in production. |
| User calls to product | Users sign in through a product client (e.g. `product-sample-web`, audience = the product's `apiAudience`). The product validates `iss`, `aud`, signature, reads `sub`, takes the company from its URL and **verifies** every request with `GET /api/v1/service/access?companyId&userSubject[&featureCode]` (user status, ACTIVE membership with `company.products.launch`, company status, entitlement). Fail closed when Core is unreachable. |
| Tenant identification | `companyId` (UUID); `companyCode` is sent at provisioning for display and URLs. |
| Provisioning | Configured per product version: `provisioning.mode` NONE / SYNC / ASYNC, `path` (may contain `{companyId}`, `{companyCode}`), `timeoutSeconds`, `maxAttempts`. `PUT {apiBaseUrl}{path}` with body `{jobId, companyId, companyCode, companyName, productCode, configVersion, async, callbackUrl}` and headers `Authorization`, `Idempotency-Key: <jobId>`; `DELETE {apiBaseUrl}{path}` to deprovision (404/410 = already gone). 2xx = done; ASYNC: 202 = accepted, the product then calls `POST /api/v1/service/provisioning/jobs/{jobId}/callback {outcome: SUCCEEDED|FAILED, message}` before the callback deadline (default 15 min). Other 4xx permanent; network errors, timeouts, 5xx, 408, 429, 401 retried. |
| When | A PROVISION job is created when a company becomes entitled to a product (in the subscription change's transaction). When the entitlement ends, a DEPROVISION job is scheduled after the data-retention period (D-5, 90 days) and cancelled if the company is entitled again before then. |
| Usage | `POST /api/v1/service/usage/consume` / `release` `{companyId, featureCode, quantity, idempotencyKey}`; atomic limit check (409 `LIMIT_EXCEEDED`); keys are idempotent per company and product. |
| Launch | `GET /api/v1/companies/{companyId}/products` returns `launchUrl` = `appUrl` + `launch.path` once the tenant is PROVISIONED, the entitlement allows access and the company is active. |
| Errors | RFC 9457 problem details with stable `code` values. |
| Versioning | Path-versioned (`/v1`); additive changes only within a version; deprecation ≥ 6 months. |
| Audit | Products send `X-Correlation-Id`; Core logs every provisioning call and result. |
| Data | Products never connect to the Core DB. |

## 8. Frontend architecture (implemented in Phase 8)

One Angular 22 app (standalone components, signals, zoneless, OnPush everywhere) serving both
audiences: OCCPL staff see the platform area, company users their company workspace; platform staff
open the same company workspace with extra actions.

```
src/app/
  core/auth/      OIDC Authorization Code + PKCE client (AuthService), auth interceptor, guards
  core/api/       typed API clients per area + models.ts (shapes of the Core API)
  core/session/   SessionService: GET /me (platform permissions, memberships), home URL
  core/http/      correlation-id and RFC 9457 error interceptors
  shared/ui/      page header, status badge, pager, accessible action dialog, format pipes
  shared/forms/   field errors, mapping of server validation errors onto form controls
  layout/shell/   header, navigation by permission, MFA notice, global error alert
  features/platform/  dashboard, companies (list, create), users (list, detail, platform roles),
                      products (list, register, detail: versions, features), plans (list, editor,
                      detail), subscriptions, provisioning jobs, audit log
  features/company/   workspace (tabs by permission): overview (details, status, settings,
                      contacts, history), products (launcher), subscription, users & invitations,
                      usage, audit log
  features/{auth,profile,invitations}  sign-in callback and status pages, own profile, accept invitation
```

- **Sign-in**: own small OIDC client (no third-party library): discovery, Authorization Code + PKCE
  (S256), state check, token exchange, refresh shortly before expiry, RP-initiated logout. Tokens are
  kept in memory only; the PKCE verifier and state live in `sessionStorage` only during the redirect.
  A reload signs in again through Keycloak's SSO session. Return URLs are restricted to in-app paths.
- **MFA**: platform staff whose token lacks `amr=otp` see a notice and "Sign in with authenticator"
  (re-authentication with `prompt=login`); Core withholds platform permissions meanwhile.
- **Authorization in the UI** is presentation only: navigation, tabs, buttons and guards follow
  `GET /me` (platform permissions + membership permissions); Core enforces every rule and its
  403/404/409 responses are shown to the user.
- **Routing**: lazy-loaded pages; `paramsInheritanceStrategy: 'always'` so pages receive `:companyId`;
  a route-reuse strategy re-creates pages when path parameters change (no stale data when switching
  company or product); page titles "<Page> · OCCPL Admin".
- **Forms**: typed reactive forms; client validation mirrors the API's rules; server field errors
  (`errors[]`) are shown under the matching field, other errors above the form; optimistic locking
  with `If-Match` on company, settings and product updates.
- **Runtime configuration**: `/config.json` (API base URL, OIDC issuer/client) is generated by the
  container at start-up and validated before bootstrap, so one image is promoted through every
  environment.
- **Accessibility and layout**: skip link, labelled fields with `aria-describedby` errors, native
  `<dialog>` for confirmations, visible focus, tables become stacked cards below 640 px, navigation
  collapses into a menu below 900 px; design tokens with light/dark themes.
- **Tests**: Vitest (47 tests: sign-in, guards, session, interceptors, shell, routing, forms, plan
  editor, member management, API helpers); ESLint (angular-eslint incl. template accessibility rules)
  and Prettier in CI; browser walkthrough against the Docker stack (see CHANGELOG, Phase 8).

## 9. Scalability path (introduce only when measured)

1. Stateless Core replicas behind Nginx/LB; HikariCP sized per instance; Redis for shared state.
2. Read replicas for reporting / heavy listings.
3. Partition high-volume append tables (`audit_log`, `usage_record`) by month.
4. Extract a module (likely `entitlement` or `audit`) into a service if it scales differently.
5. Message broker if outbox fan-out becomes a bottleneck.
6. Sharding by company only if a single primary cannot sustain write load after the above.

## 10. Implemented foundation (Phase 2)

| Concern | Implementation |
|---|---|
| Errors | `shared.error`: `ErrorCode` (stable codes), `ApiException`, `GlobalExceptionHandler` — every controller and framework error becomes RFC 9457 with `code` and `correlationId`; 500s never leak messages |
| Correlation | `CorrelationIdFilter` (highest precedence): accepts a well-formed `X-Correlation-Id` or generates one; MDC + response header |
| Security | Stateless resource server; issuer + audience (`occpl-core`) validation; 401/403 as problem details with `WWW-Authenticate`; CORS allowlist; CSP/HSTS/frame/referrer headers; API docs only when `API_DOCS_ENABLED=true` |
| Actuator | Separate management port 8081; only `health` (liveness/readiness), `info`, `prometheus` exposed; everything else denied |
| Logging | Spring Boot structured logging (ECS JSON) by default; readable pattern with correlation id in `local` profile |
| Persistence | HikariCP, `ddl-auto=validate`, `open-in-view=false`, UTC JDBC time zone; Flyway uses the migrator account, the app uses a DML-only account |
| Ids / time / paging | `UuidV7`, UTC `Clock` bean, `PageRequests` (max 100, sort allowlist, `id` tie-breaker), `PageResponse` |
| Threads | Java virtual threads enabled for request handling |

## 11. Cross-cutting

- Time: all timestamps stored as UTC `DATETIME(6)`; `Clock` bean injected for testability.
- IDs: UUID v7 (time-ordered) stored as `BINARY(16)` for index locality (ADR-0003).
- Optimistic locking (`version` column) on mutable aggregates.
- Observability: Spring Boot Actuator (liveness/readiness), Micrometer → Prometheus, JSON logs
  (logstash encoder) with correlation id, user id and company id (no PII/tokens), Grafana
  dashboards and alert rules (provisioning failures, subscription job failures, 5xx rate,
  pool saturation).
