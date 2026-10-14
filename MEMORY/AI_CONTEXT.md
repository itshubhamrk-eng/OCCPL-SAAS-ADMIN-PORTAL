# AI Context (read first)

**Project**: OCCPL Innovations multi-product SaaS platform. The Core Platform manages companies,
users/memberships, roles/permissions, product catalogue, plans, subscriptions, entitlements,
provisioning, billing integration (Razorpay), audit, notifications. Products are separate services
that own their data. Core contains no product business logic.

**Current phase**: Phase 10 (Deployment) code complete 2026-10-14: Terraform, compose bundle,
deploy pipeline and runbook (`infra/aws/`). Not yet applied to an AWS account (D-1e: needs account
access, Route 53 zone, SES production access, GitHub environments). Open follow-ups: I-1, C-8, C-9,
C-10, PC-4, PC-5, S-9, PR-5, PR-6, UI-4 (settings page), UI-5, UI-6, B-3..B-8 (Razorpay live test,
GST D-11, …), H-5 (pre-launch load test on production), H-6, H-7, D-1e, D-2 drill, D-4..D-6.

**Hosting (D-13/D-14, ADR-0011)**: AWS ap-south-1, EC2 + Docker Compose behind an ALB, RDS MySQL 8.4 LTS, ElastiCache, SES, Secrets Manager, ECR, Terraform.

**Domain (D-12)**: `occplsoftech.com` — admin., api., auth., `<product>.`, `*.staging.` reserved (DEPLOYMENT.md §2).

**No AWS staging (D-15)**: one AWS environment, production; releases are tried on the local compose
stack, then approved in GitHub (`production` environment) before the rolling deploy.

**Stack (ADR-0007)**: Java 25 LTS, Spring Boot 4.1.1 (Framework 7, Security 7, Hibernate 7.4,
**Jackson 3 = `tools.jackson.*`**, Flyway 12.4 + `spring-boot-starter-flyway`), Spring Modulith 2.1,
springdoc 3.1, MySQL 8.4 LTS in production (Amazon RDS, D-14; tests default to 8.4, CI also 9.7 via `-Doccpl.test.mysql-image=mysql:9.7`), Keycloak 26.8.1 (self-hosted), Angular 22.2 (zoneless, Vitest,
TS 6), Node 24 for builds, Nginx 1.30 unprivileged, Redis 8.10 (rate limiting, Bucket4j/Lettuce).

**Layout**: `backend/core-platform` (package `com.occpl.platform.<module>.{api,web,internal}`,
`shared` is OPEN), `backend/product-sample` (independent sample product `com.occpl.sample`, own DB),
`backend/e2e-tests` (Keycloak + Core in-process + sample as a process), `frontend/admin-portal` (`core/`, `layout/`, `features/`), `infra/docker`
(compose, realm, MySQL users), `infra/aws` (Terraform stacks, production compose bundle, deploy
scripts, RUNBOOK.md), `.github/workflows/{ci,deploy}.yml`.

**Commands**: backend `cd backend && mvn verify` (Docker needed for *IT); frontend
`npm run format:check && npx ng lint && npm test && npx ng build`.

**Deployment rules**: images are tagged with the git SHA (immutable ECR tags); instance config comes
from SSM `/occpl/<env>/config` + Secrets Manager via `render-env.sh` — add new Core settings there
and in Terraform `secrets.tf`; production realm = `make-production-realm.py` output (CI checks it is
in sync); `audit_log` is append-only by triggers (needs `log_bin_trust_function_creators=1`);
`API_BASE_URL` of the portal includes `/api/v1`.

**Key rules**
- Only a module's `api` package (`@NamedInterface("api")`) is public; `ModularityTests` enforce it.
- Tenant from path `/companies/{companyId}` + server-side membership check; cross-tenant → 404.
- JWT = identity only; memberships/entitlements/status read from Core (≤ 30 s cache).
- UUID v7 `BINARY(16)`, UTC `DATETIME(6)`, `version` optimistic lock, Flyway only,
  `ddl-auto=validate`, expand/contract migrations, migrator vs DML-only app DB user.
- Errors: throw `ApiException(ErrorCode, detail)`; never return entities; paging via `PageRequests`.
- Module deps: identity→access,audit; company→identity,audit; membership→identity,access,company,audit,notification,entitlement;
  catalog→identity,audit; subscription→identity,company,catalog,audit; entitlement→subscription,catalog,company;
  provisioning→catalog,company,entitlement,audit,notification; billing→subscription,company,audit; integration→catalog,entitlement,identity,membership,provisioning;
  access/audit/notification→shared only. `MembershipQueries` is the documented read-only cross-module SQL exception.
- List endpoints take `SortParams` (raw `sort` values) — a plain `List<String> sort` gets split at commas.
- Background jobs: `@Scheduled`, multi-instance safe (SKIP LOCKED / idempotent UPDATE); disabled in tests
  (`occpl.scheduling.enabled=false`) — call `NotificationDispatcher.dispatchDue()` / `InvitationService.expireOverdue()`.
- Invitation tokens: SHA-256 stored; new users → Keycloak execute-actions email (redirect to accept URL),
  existing users → `Notifications.enqueue` email. Tests capture tokens from the IdP mock or the notification row.
- Catalog: `ProductCatalog` API (find, features, currentConfig) for plans/provisioning; config JSON Schema at
  `src/main/resources/catalog/product-config-v1.schema.json` (networknt 3.x, Jackson 3); multi-field errors via
  `ValidationException`.
- Subscriptions: `SubscriptionStateMachine` is the single transition table; every change goes through
  `SubscriptionService.record` (history + audit + `SubscriptionChanged` → `EntitlementRecomputer`, same tx).
  Sweeps: `SubscriptionSweeper.sweep()` (row lock + re-check). D-5: grace 7d, suspended expiry 90d, retention 90d.
- Entitlements: `Entitlements` API (check/consume/release/memberLimit); usage limit enforced in one
  conditional UPDATE in `UsageCounters`; windows in UTC.
- Integration tests share one DB and run in any order: never assume an empty table; clean up what a test relies on.
- Shared test containers: `MySqlTestDatabase`, `KeycloakTestServer` (GreenMail on host port 3025).
- Current user: `CurrentUser.require()` (identity api); audit actor via `shared.security.Actor`.
- Service tokens: JWT with `client_id` → `ServicePrincipal` (authority SERVICE), allowed only on `/api/v1/service/**`
  (security chain). Product = `ProductCatalog.findByServiceClientId` via `integration.internal.ServiceCaller`.
  `AuditLogger.record` audits SERVICE actors with `actor_client_id`. Tests: `MockTokens.registerService(token, clientId)`.
- Provisioning: `EntitlementsChanged` (from `EntitlementRecomputer`) → `ProvisioningService` creates jobs in the same tx;
  `ProvisioningRunner.runDue()` (scheduled every 5 s; call directly in tests) claims, calls the product outside tx,
  records under row lock. Tests fake products with `support.ProductServiceStub` (also serves `/token`).
- Billing: `PaymentGateway` port, `RazorpayGateway` (JDK HttpClient, basic auth); invoice row committed as
  CREATING before the gateway call; `uk_invoice_open` (generated `open_key`) = one open/paid invoice per period.
  Webhooks: `PaymentEvents.receive` (signature, then `process` in a tx; dedupe by `payment_webhook_event`).
  Payments applied via `subscription.api.SubscriptionBilling.applyPayment(subscriptionId, periodStart)`
  (APPLIED / ALREADY_COVERED / NOT_APPLICABLE). Tests fake Razorpay with `support.RazorpayStub`;
  call `InvoiceService.createDueInvoices()` directly (scheduling disabled in tests).
- Company paths: `CompanyPathGuard` (MappedInterceptor bean in MembershipConfiguration, not @Component —
  @WebMvcTest slices load every HandlerInterceptor/WebMvcConfigurer bean) denies before body binding;
  `@tenant.check` reuses its lookup. `EndpointSecurityIT` enumerates all mappings: a new endpoint needs
  @PreAuthorize (or an allow-list entry with a reason) and, under /companies/{companyId}, @tenant.check
  or a platform authority; nested id path variables need an entry in its foreign-resource map.
- Service tokens to products: `ServiceTokenProvider.token(apiAudience)` requests `scope=<apiAudience>`
  (optional client scope per product in the realm); cached per audience.
- Product URLs: local/link-local hosts only with `occpl.catalog.allow-local-urls` (true in tests/compose).
- Overload: SQLTransientConnectionException → 503 SERVICE_BUSY + Retry-After (advice + OverloadFilter).
- `spring.modulith.moments.enabled=false` — Moments would re-enable @Scheduled in tests.
- Load tests: `load-tests/` (setup.py, seed_sql.py, k6/core.js, RESULTS.md); backups: `infra/backup/`
  (MySQL Shell; DRILL.md). k6/trivy are not installed in the sandbox: `go install` them.
- Executable jars use classifier `exec` (Dockerfiles copy `*-exec.jar`); core-platform also builds a test-jar
  (KeycloakTestServer, KeycloakLoginFlow, MySqlTestDatabase, TestData) used by e2e-tests.
- Keycloak realm secrets via env: KEYCLOAK_ADMIN_CLIENT_SECRET, PROVISIONING_CLIENT_SECRET, PRODUCT_SAMPLE_CLIENT_SECRET.
- Company endpoints: `@PreAuthorize("@tenant.check(#companyId, '<company perm>', '<platform perm>')")`,
  then `TenantContext.current()`. Platform endpoints: `hasAuthority('<platform perm>')`.
- Platform permissions are withheld unless the JWT `amr` contains `otp` (MFA).
- Native queries: select `BIN_TO_UUID(col)`; interface projections cannot map BINARY(16) to UUID.
- Mockito with a catch-all stub: use `willReturn(..).given(mock).method(..)`.
- Keycloak tests: `KeycloakIdentityIT` + `KeycloakLoginFlow` (parses the 26.8.1 login theme).
- Tests: Spring tests use `@ActiveProfiles("test")` (`application-test.yml`); never add a test
  `application.yml` (it shadows the main one).
- Portal CSP forbids inline scripts — keep `inlineCritical: false`.
- Portal: zoneless + OnPush everywhere (lint rule). State in signals; if a non-signal (form array,
  plain field) changes after an `await`, call `ChangeDetectorRef.markForCheck()`. API shapes in
  `core/api/models.ts`; clients per area in `core/api/*.api.ts`; `SessionService.can(companyId, companyPerm,
  platformPerm)` for UI permission checks; route inputs: inputs absent from route data become undefined.
- Portal tests: `src/app/testing/fixtures.ts` (TEST_CONFIG, FakeBrowser, me()); browser walkthrough
  scripts are not in the repo (UI-6).
- Never mark backlog items done without passing tests; never claim tests pass without running.

**Environment notes (cloud sandbox)**: JDK 25 at `/opt/jdk25`, Node 24 at `/opt/node24`; start
`dockerd` manually; `docker build` stages have no network, so verify images with host-built
artifacts. Docker Hub rate-limits: pull via `mirror.gcr.io/library/<image>` and re-tag.

**Docs map**: PROJECT (scope, versions, decisions, risks), ARCHITECTURE, DATABASE, API_SPEC,
SECURITY, BACKLOG, DEPLOYMENT, CHANGELOG, ADR/.
