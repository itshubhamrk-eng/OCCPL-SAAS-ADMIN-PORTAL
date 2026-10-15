# ADR-0012: Launch on a single VPS; the AWS design stays ready

- Status: Accepted · Date: 2026-10-15 · Supersedes the launch target of ADR-0011 (the AWS design remains valid)

## Context
ADR-0011 describes a highly available AWS setup (two EC2 instances, Multi-AZ RDS, ElastiCache, ALB,
WAF), about $630–660 a month. There are no customers yet; the owner decided to start with one VPS
(D-16). AWS account verification was also pending for days, which blocked creating servers.

## Decision
Run everything on **one VPS** with Docker Compose (`infra/vps`): Caddy (automatic Let's Encrypt
certificates), the admin portal, Core Platform, Keycloak (production image, realm import), MySQL 8.4
and Redis in containers. Images are built once per commit by GitHub Actions and published to the
GitHub container registry (`release.yml`); a manual approval on the `production` environment gates the
deploy over SSH (`bin/deploy.sh`, automatic rollback). Email goes through the existing
`tech@occplsoftech.com` SMTP mailbox until SES is approved (SMTP over SSL or STARTTLS is configurable).
Backups: the Phase 9 scripts (nightly dump, 5-minute binary-log increments) with an off-host copy in a
write-only S3 bucket (`infra/aws/shared/backups.tf`). DNS stays in Route 53.

## Consequences
+ About a tenth of the cost, no cloud account dependencies at launch, the same containers as
  development, the same database engine and version (MySQL 8.4) as the AWS plan.
+ Same pipeline guarantees: immutable SHA tags, approval, smoke test, automatic rollback.
− Single point of failure: a host failure or reboot is an outage; a deploy restarts the containers (30 s
  to 1.5 min). Recovery is a restore onto a new server (tested: seconds for the drill data set).
− No managed database failover, no Multi-AZ, no web firewall; the firewall (ufw), fail2ban, the
  Keycloak admin restriction and rate limiting are the controls.
− Backup tools need MySQL Shell 9.x (`util.dumpBinlogs`); they run it from the `mysql:9.7` image against
  the 8.4 server (tested). The server and the backups are our responsibility: patching, disk space,
  monitoring (heartbeat + external uptime check).
Revisit (move to `infra/aws`) when availability requirements or load exceed one machine.
