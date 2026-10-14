# OCCPL-SAAS-ADMIN-PORTAL

Central multi-product SaaS management platform for OCCPL Innovations (Core Platform + Super
Admin / Company Admin portals).

**Status:** Phases 1–8 and Billing (Razorpay) complete — awaiting approval for Phase 9 (hardening).
Status report: [docs/OCCPL-Phase-Plan-and-Status.pdf](docs/OCCPL-Phase-Plan-and-Status.pdf).

Stack: Java 25 · Spring Boot 4.1 · Angular 22 · MySQL 9.7 · Keycloak 26.8 · Docker Compose.

```bash
cd infra/docker && cp .env.example .env && docker compose up --build
```

See [DEPLOYMENT.md](DEPLOYMENT.md) for local development and test commands.

| Document | Contents |
|---|---|
| [PROJECT.md](PROJECT.md) | Scope, current-state assessment, assumptions, open decisions |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Modules, dependency rules, integration contract, frontend |
| [DATABASE.md](DATABASE.md) | Entities, constraints, state machine, usage limits, migrations |
| [API_SPEC.md](API_SPEC.md) | API conventions and endpoint inventory |
| [SECURITY.md](SECURITY.md) | AuthN/AuthZ, tenant isolation, data protection |
| [BACKLOG.md](BACKLOG.md) | Prioritized tasks with acceptance criteria |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Environments, pipeline, rollback, backups, alerts |
| [CHANGELOG.md](CHANGELOG.md) | Completed changes |
| [ADR/](ADR/README.md) | Architecture decision records |
| [MEMORY/AI_CONTEXT.md](MEMORY/AI_CONTEXT.md) | Condensed context for coding sessions |
