# Changelog

All notable changes to this project are documented here.

## [Unreleased]

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
