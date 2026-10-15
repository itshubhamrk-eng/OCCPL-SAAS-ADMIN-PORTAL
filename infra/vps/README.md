# Single-server deployment (ADR-0012, D-16)

The platform on **one VPS** with Docker Compose: the launch target. The AWS design of ADR-0011
(`infra/aws`) stays in the repository for the day the platform outgrows one machine.

```
Internet ─ :80/:443 ─ Caddy (automatic Let's Encrypt certificates, HTTP → HTTPS)
             ├── admin.<domain>  → admin-portal   (nginx, static Angular)
             ├── api.<domain>    → core-platform  (Spring Boot)
             └── auth.<domain>   → keycloak       (/admin and the master realm only from ADMIN_IPS)
           internal network:  MySQL 8.4 (occpl_core, keycloak) · Redis (rate limiting)
           host:              /var/backups/occpl (nightly dump + 5-minute binary-log increments)
                              → off-host copy in S3 (infra/aws/shared/backups.tf)
```

| Path | Purpose |
|---|---|
| `docker-compose.yml`, `Caddyfile`, `mysql/01-init.sh` | the stack |
| `.env.example` | documents `/opt/occpl/.env` (the real file is generated on the server) |
| `bin/install.sh` | one-time server preparation (Docker, firewall, fail2ban, swap, cron, `occpl` user) |
| `bin/init-env.sh` | writes `.env` with generated passwords |
| `bin/deploy.sh <sha>` | starts a release, waits until ready, rolls back automatically if it fails |
| `bin/backup.sh`, `bin/healthcheck.sh` | cron jobs (backups with off-host copy; heartbeat) |
| `../backup/` | MySQL Shell backup and restore scripts (copied to `/opt/occpl/backup`) |
| `../scripts/smoke-test.sh` | public checks after a deploy |

Images: `ghcr.io/<owner>/occpl-{core-platform,admin-portal,keycloak}:<git sha>`, built by
`.github/workflows/release.yml` after CI passes on `main`; the deploy step needs the approval of the
`production` GitHub environment. Setup and operations: [RUNBOOK.md](RUNBOOK.md).

## What a single server means

| | |
|---|---|
| Availability | One machine: a reboot or a host failure is an outage until it is back. A deploy restarts the containers (about 30 s to 1.5 min). |
| Data | Protected by the nightly dump, the 5-minute log increments and the S3 copy (RPO about 5 minutes + upload). A server loss is recovered on a new server from S3 (RUNBOOK §6). |
| Capacity | 4 vCPU / 8 GB handles the load measured in Phase 9 (210 req/s on 4 shared vCPUs); resize the VPS when it does not. |
| Security | Only 22, 80 and 443 are open; databases, Redis and management ports are not published beyond the host. |
