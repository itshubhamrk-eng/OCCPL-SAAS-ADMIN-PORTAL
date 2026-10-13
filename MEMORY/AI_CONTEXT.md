# AI Context (read first)

**Project**: OCCPL Innovations multi-product SaaS platform. The Core Platform manages companies,
users/memberships, roles/permissions, product catalogue, plans, subscriptions, entitlements,
provisioning, billing integration, audit, notifications. Products are separate services that
own their data. Core contains no product business logic.

**Current phase**: Phase 1 documented (2026-10-13); **waiting for approval before Phase 2**.
No code exists yet.

**Stack**: Java 21, Spring Boot 3.5.x (D-1 open: 4.x alternative), Spring Security resource
server, JPA/Hibernate, Flyway, MySQL 8.4, Redis 7, Keycloak 26 (OIDC), Angular 22 strict,
Docker Compose, Nginx. Spring Modulith + ArchUnit for module boundaries. Testcontainers, k6.

**Key rules**
- Modular monolith `com.occpl.platform.<module>.{api,web,internal}`; only `api` is public;
  no cross-module JPA relations; events instead of cycles (ARCHITECTURE.md §4).
- Tenant from path `/companies/{companyId}` + server-side membership check; cross-tenant → 404.
- JWT = identity only; memberships/entitlements/status read from Core (≤ 30 s cache).
- UUID v7 `BINARY(16)` ids, UTC `DATETIME(6)`, `version` optimistic lock, Flyway only,
  `ddl-auto=validate`, expand/contract migrations.
- Subscription state machine & usage-limit semantics: DATABASE.md §4–5.
- Outbox + durable provisioning jobs; no Kafka/K8s until measured need.
- RFC 9457 errors with stable `code`; pagination max 100; `Idempotency-Key` on creates/payments.
- Never mark backlog items done without passing tests; never claim tests pass without running.

**Docs map**: PROJECT (scope, assumptions, decisions D-1..D-10), ARCHITECTURE, DATABASE,
API_SPEC, SECURITY, BACKLOG, DEPLOYMENT, CHANGELOG, ADR/.

**Next task**: Phase 2 — Foundation (BACKLOG F-1…F-6), after approval.
