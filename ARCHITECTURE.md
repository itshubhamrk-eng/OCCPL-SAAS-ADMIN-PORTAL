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
| `product-sample` (Spring Boot) | Reference product implementing the integration contract (Phase 7) | own schema |
| Nginx | TLS termination, static assets, reverse proxy, security headers | — |
| Redis | Entitlement cache, rate-limit buckets (added in Phase 3, ADR-0006) | ephemeral |

## 3. Repository layout (proposed for Phase 2)

```
/backend/core-platform        Maven project (Spring Boot)
/backend/product-sample       Maven project (Phase 7)
/backend/integration-contract OpenAPI spec + shared DTO module for product services (no logic)
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
| 0 | `shared` | error model, paging, ids, clock, correlation id, security chain, rate limiting, `Actor`, `JwtPrincipalResolver` | — |
| 1 | `identity` | users, IdP subject mapping, profiles, `CurrentUser` resolution, MFA policy, Super Admin bootstrap, platform role assignment API, Keycloak admin client | shared, access, audit |
| 2 | `company` | company, settings (allowed email domains), contacts, lifecycle + history; publishes `CompanyCreated` | shared, identity, audit |
| 3 | `membership` | memberships, invitations (delivery via Keycloak or email), `@tenant` company authorization, `TenantContext`, `GET /me` | shared, identity, company, access, audit, notification (entitlement for seat checks in Phase 6) |
| 4 | `access` | permission catalogue, roles, platform and membership role assignments, permission resolution | shared |
| 5 | `catalog` | products, immutable configuration versions (JSON Schema validated), features; `ProductCatalog` API | shared, identity, audit |
| 6 | `subscription` | plans, plan features/limits, company subscriptions, history, state machine | shared, company, catalog, audit |
| 7 | `entitlement` | computed company-product entitlements, limits, usage counters | shared, catalog, subscription (events + read api) |
| 8 | `provisioning` | provisioning jobs, outbox dispatch, product-service clients | shared, catalog, company, entitlement, audit, notification |
| 9 | `billing` | invoices, payment transactions, gateway port & webhooks | shared, subscription, audit |
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
5. `RateLimitFilter` (Redis token bucket) keyed by user id, or client IP when unauthenticated.
6. Method security. Platform: `@PreAuthorize("hasAuthority('platform.roles.manage')")`. Company:
   `@PreAuthorize("@tenant.check(#companyId, 'company.users.read', 'platform.companies.read')")` loads
   the caller's ACTIVE membership in the path's company (404 if none), checks company status and
   permission, and stores `TenantContext` (companyId, membershipId, permissions) for the request.
   Services additionally scope every repository query by `companyId`.
8. Global exception handler → RFC 9457 problem details (see API_SPEC.md §2).

## 6. Asynchronous processing

- **Transactional outbox** (`outbox_event` table) written in the same transaction as the state
  change. A scheduled dispatcher (one active instance via DB lock / ShedLock) publishes events
  in-process and drives `provisioning_job` execution.
- Provisioning jobs: durable rows with status, attempt count, `next_attempt_at`, exponential
  backoff with jitter, max attempts, then `FAILED_PERMANENT` + alert + Super Admin retry button.
- Scheduled jobs: subscription renewals/expiry sweeps, trial expiry, PAST_DUE → SUSPENDED after
  grace, usage-counter period resets, invitation expiry. All idempotent and batch-paginated.
- No Kafka/RabbitMQ initially (ADR-0004). Revisit if outbox throughput or fan-out to many
  product services becomes a measured bottleneck.

## 7. Product integration contract (summary — full spec in Phase 7)

| Concern | Contract |
|---|---|
| Service identity | Each product service is a Keycloak confidential client. Core → Product calls use client-credentials tokens with `aud=<product-api-audience>`; Product → Core calls use tokens with `aud=occpl-core` and scope `entitlements:read` / `usage:write`. mTLS optional in production. |
| User calls to product | Browser obtains token via SSO; product validates `iss`, `aud`, signature; reads `sub`; resolves tenant from its URL/host or `X-Company-Id` and **verifies** it against Core's entitlement/membership API (cached ≤ 60 s). |
| Tenant identification | `companyId` (UUID) + `productCode`; never trust a client-supplied tenant without verification. |
| Provisioning | `PUT /provisioning/v1/tenants/{companyId}` (idempotent upsert, `Idempotency-Key` header), `DELETE` for deprovision, `GET` for status. Responses: 200/201 done, 202 accepted (async, product calls back `POST /api/v1/provisioning/callbacks`), 4xx permanent, 5xx/timeout retryable. |
| Entitlement check | `GET /api/v1/entitlements/check?companyId=&productCode=&feature=` and `POST /api/v1/usage/consume` (atomic limit check). |
| Errors | RFC 9457 problem details with stable `code` values. |
| Versioning | Path-versioned (`/v1`); additive changes only within a version; deprecation ≥ 6 months. |
| Audit | Products send `X-Correlation-Id`; Core logs every provisioning call and result. |
| Data | Products never connect to the Core DB. |

## 8. Frontend architecture

One Angular app, standalone components, lazy-loaded feature areas:

```
src/app/
  core/        auth (OIDC PKCE client), http interceptors (auth, correlation id, error), guards,
               session/context store (signals), typed API clients generated from OpenAPI
  shared/      design-system components (table, form fields, dialogs, status badges), pipes
  layout/      platform-shell (Super Admin), company-shell (Company Admin), company switcher
  features/platform/{dashboard,companies,users,products,plans,subscriptions,entitlements,
                     provisioning,audit,settings}
  features/company/{dashboard,profile,products,subscription,users,roles,usage,billing}
```

- Runtime configuration: `/config.json` (API base URL, OIDC issuer/client) is generated by the
  container at start-up from environment variables and validated before bootstrap, so one image is
  promoted through every environment.
- HTTP: functional interceptors add `X-Correlation-Id` to Core API calls only and normalise RFC 9457
  errors into a typed `ApiError`; non-field errors are shown in the shell's alert region.
- Tests: Vitest via `@angular/build:unit-test`; ESLint (angular-eslint) and Prettier in CI.
- Auth: OIDC Authorization Code + PKCE (e.g. `angular-auth-oidc-client`), tokens in memory,
  refresh via IdP refresh-token rotation. No tokens in `localStorage`.
- `GET /api/v1/me` returns the user's memberships and effective permissions; navigation and
  guards use it for UX only.
- Design tokens as CSS custom properties (`--occpl-color-primary`, spacing, typography) with
  light/dark themes; no hard-coded colours in components.
- Strict TypeScript, Reactive Forms with typed form groups, centralized error toast/handler.

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
