# Deployment

Status: Local stack, Dockerfiles and CI implemented in Phase 2. Production deployment in Phase 10.

## 1. Environments

| Env | Purpose | Infrastructure | Data |
|---|---|---|---|
| local | Development | Docker Compose on developer machine | Synthetic seed |
| test (CI) | Automated tests | Testcontainers (MySQL, Keycloak, Redis) | Ephemeral |
| staging | Release verification, load tests | Same topology as prod, smaller sizes | Synthetic / anonymized |
| production | Customers | Ubuntu 24.04 LTS VMs (≥ 2 app nodes) behind LB/Nginx, managed or replicated MySQL 9.7 LTS, Redis, Keycloak (2 nodes) | Real |

Configuration: Spring profiles `local` and `test` exist (staging/prod use the production-safe defaults in
`application.yml` plus environment variables); the portal reads a runtime `config.json`
generated from environment variables. All secrets injected at runtime (env vars / secret manager); none in
Git or images.

### Local development

Prerequisites: Docker, JDK 25, Maven 3.9+, Node.js 24 LTS.

```bash
# Full stack in containers
cd infra/docker && cp .env.example .env   # edit the passwords
docker compose up --build
#   Portal http://localhost:4200 · API http://localhost:8080 · Swagger http://localhost:8080/swagger-ui.html
#   Health http://localhost:8081/actuator/health · Keycloak http://localhost:8180

# Or run the backend from source against the compose MySQL/Keycloak
docker compose up -d mysql keycloak
cd backend/core-platform
SPRING_PROFILES_ACTIVE=local DB_USERNAME=occpl_app DB_PASSWORD=<app> \
  DB_MIGRATOR_USERNAME=occpl_migrator DB_MIGRATOR_PASSWORD=<migrator> mvn spring-boot:run

# Frontend dev server (uses public/config.json)
cd frontend/admin-portal && npm ci && npm start
```

Checks a contributor runs before pushing: `mvn verify` in `backend/` (needs Docker for
Testcontainers) and `npm run format:check && npx ng lint && npm test && npx ng build` in
`frontend/admin-portal`.

### Core Platform configuration (environment variables)

| Variable | Required | Purpose |
|---|---|---|
| `DB_URL` | yes | JDBC URL of `occpl_core` |
| `DB_USERNAME` / `DB_PASSWORD` | yes | DML-only application account |
| `DB_MIGRATOR_USERNAME` / `DB_MIGRATOR_PASSWORD` | yes (prod) | DDL account used by Flyway |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | no (20 / 5) | HikariCP sizing per instance |
| `OIDC_ISSUER_URI` | yes | Expected `iss` (and discovery URL unless a JWK set URI is given) |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI` | no | Internal JWKS URL when the issuer URL is not reachable from the container |
| `OIDC_AUDIENCE` | no (`occpl-core`) | Required `aud` |
| `CORS_ALLOWED_ORIGINS` | no (none) | Comma-separated portal origins |
| `API_DOCS_ENABLED` | no (`false`) | OpenAPI/Swagger UI; keep `false` in production |

Admin portal container: `API_BASE_URL`, `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `CSP_CONNECT_SRC`
(space-separated origins of the API and IdP).

## 2. Initial production topology

```
Internet → LB / Nginx (TLS, static portal, reverse proxy) ─┬─ core-platform #1 ─┐
                                                            └─ core-platform #2 ─┼─ MySQL primary (+ replica, PITR backups)
                                       Keycloak ×2 ──── own DB                   ├─ Redis
                                       product services (own hosts/DBs)          └─ SMTP provider
Monitoring: Prometheus + Grafana + Alertmanager, Loki (logs); error tracking (e.g. Sentry)
```

Kubernetes is not used initially (ADR-0004 rationale); the containers are k8s-ready
(stateless, health probes, env config) if operational needs later justify it.

## 3. Build & release pipeline (CI/CD)

1. PR: `mvn verify` (unit + integration with Testcontainers, module verification),
   `npm ci && ng lint && ng test --watch=false && ng build`, dependency scan, Flyway validate.
2. Main: build versioned images (`core-platform:<git-sha>`, `admin-portal:<git-sha>`), Trivy
   scan, push to registry.
3. Deploy staging: run Flyway migration job (migrator credentials) → rolling restart of app
   nodes → smoke tests → (optional) load test.
4. Promote the same image digests to production with manual approval.

## 4. Release & rollback

- Migrations are expand/contract and backward compatible, so the previous app version runs on
  the new schema. Rollback = redeploy previous image digest; DB is not rolled back.
- Destructive (contract) migrations ship only in a release after the code no longer uses the
  old structure.
- Rolling deploy: drain node from LB (readiness DOWN) → stop → start new → readiness UP.

## 5. Backups & recovery (to be verified in H-4)

- MySQL: daily full backup (e.g. Percona XtraBackup or managed snapshots) + binlog shipping for
  point-in-time recovery; encrypted, stored off-host, 35-day retention.
- Keycloak DB: same policy.
- Quarterly restore drill to staging with measured RPO/RTO recorded here.

## 6. Monitoring & alerts (initial set)

| Alert | Condition |
|---|---|
| API error rate | 5xx > 1% for 5 min |
| Latency | p95 > SLO for 10 min |
| DB pool | Hikari pending > 0 for 5 min or active/max > 90% |
| Provisioning | any job FAILED_PERMANENT; FAILED_RETRYABLE backlog > threshold |
| Subscription jobs | scheduled sweep failed or not run in expected window |
| Outbox lag | oldest unpublished event > 5 min |
| Webhooks | signature failures spike |
| Infra | disk > 80%, replica lag > 60 s, certificate expiry < 14 days |
