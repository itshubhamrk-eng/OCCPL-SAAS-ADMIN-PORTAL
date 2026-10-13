# Changelog

All notable changes to this project are documented here.

## [Unreleased]

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
