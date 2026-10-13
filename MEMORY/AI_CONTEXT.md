# AI Context (read first)

**Project**: OCCPL Innovations multi-product SaaS platform. The Core Platform manages companies,
users/memberships, roles/permissions, product catalogue, plans, subscriptions, entitlements,
provisioning, billing integration (Razorpay), audit, notifications. Products are separate services
that own their data. Core contains no product business logic.

**Current phase**: Phase 2 (Foundation) done 2026-10-13; **wait for approval before Phase 3**
(Identity & access: BACKLOG I-1…I-5).

**Stack (ADR-0007)**: Java 25 LTS, Spring Boot 4.1.1 (Framework 7, Security 7, Hibernate 7.4,
**Jackson 3 = `tools.jackson.*`**, Flyway 12.4 + `spring-boot-starter-flyway`), Spring Modulith 2.1,
springdoc 3.1, MySQL 9.7 LTS, Keycloak 26.8.1 (self-hosted), Angular 22.2 (zoneless, Vitest,
TS 6), Node 24 for builds, Nginx 1.30 unprivileged. Redis not added yet (Phase 3).

**Layout**: `backend/core-platform` (package `com.occpl.platform.<module>.{api,web,internal}`,
`shared` is OPEN), `frontend/admin-portal` (`core/`, `layout/`, `features/`), `infra/docker`
(compose, realm, MySQL users), `.github/workflows/ci.yml`.

**Commands**: backend `cd backend && mvn verify` (Docker needed for *IT); frontend
`npm run format:check && npx ng lint && npm test && npx ng build`.

**Key rules**
- Only a module's `api` package (`@NamedInterface("api")`) is public; `ModularityTests` enforce it.
- Tenant from path `/companies/{companyId}` + server-side membership check; cross-tenant → 404.
- JWT = identity only; memberships/entitlements/status read from Core (≤ 30 s cache).
- UUID v7 `BINARY(16)`, UTC `DATETIME(6)`, `version` optimistic lock, Flyway only,
  `ddl-auto=validate`, expand/contract migrations, migrator vs DML-only app DB user.
- Errors: throw `ApiException(ErrorCode, detail)`; never return entities; paging via `PageRequests`.
- Tests: Spring tests use `@ActiveProfiles("test")` (`application-test.yml`); never add a test
  `application.yml` (it shadows the main one).
- Portal CSP forbids inline scripts — keep `inlineCritical: false`.
- Never mark backlog items done without passing tests; never claim tests pass without running.

**Environment notes (cloud sandbox)**: JDK 25 at `/opt/jdk25`, Node 24 at `/opt/node24`; start
`dockerd` manually; `docker build` stages have no network, so verify images with host-built
artifacts.

**Docs map**: PROJECT (scope, versions, decisions, risks), ARCHITECTURE, DATABASE, API_SPEC,
SECURITY, BACKLOG, DEPLOYMENT, CHANGELOG, ADR/.
