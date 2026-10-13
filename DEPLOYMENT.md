# Deployment

Status: Phase 1 plan. No deployment artifacts exist yet (created in Phase 2 / Phase 10).

## 1. Environments

| Env | Purpose | Infrastructure | Data |
|---|---|---|---|
| local | Development | Docker Compose on developer machine | Synthetic seed |
| test (CI) | Automated tests | Testcontainers (MySQL, Keycloak, Redis) | Ephemeral |
| staging | Release verification, load tests | Same topology as prod, smaller sizes | Synthetic / anonymized |
| production | Customers | Ubuntu 24.04 LTS VMs (≥ 2 app nodes) behind LB/Nginx, managed or replicated MySQL 8.4, Redis, Keycloak (2 nodes) | Real |

Configuration: Spring profiles `local`, `test`, `staging`, `prod`; Angular environment files
for API/IdP URLs only. All secrets injected at runtime (env vars / secret manager); none in
Git or images.

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
