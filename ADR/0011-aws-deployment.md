# ADR-0011: AWS deployment — EC2 with Docker Compose, RDS MySQL 8.4, Mumbai

- Status: Accepted · Date: 2026-10-14

## Context
D-13 chose AWS. The services are containers that keep no state in memory (ADR-0004: no Kubernetes
at the start). The platform was built and tested on MySQL 9.7, but Amazon RDS offers 9.7 only in
its preview environment (announced July 2026); 8.4 LTS is generally available. Customers and
personal data are mainly in India (DPDP Act 2023).

## Decision
- **Region** `ap-south-1` (Mumbai), two availability zones. `ap-south-2` (Hyderabad) is the
  candidate disaster-recovery region.
- **Compute**: EC2 instances (Ubuntu 24.04 LTS) running the existing Docker Compose services,
  one per availability zone, in an Auto Scaling group of fixed size so a failed instance is
  replaced automatically. No SSH: administration through SSM Session Manager.
- **Entry**: Application Load Balancer with an ACM certificate for `*.occplsoftech.com`,
  host-based routing (`admin.` → portal, `api.` → Core, `auth.` → Keycloak), HTTP → HTTPS
  redirect, AWS WAF managed rules and a coarse rate limit in front.
- **Database**: Amazon RDS for MySQL **8.4 LTS**, Multi-AZ, encrypted, automated backups with
  point-in-time recovery (35 days), deletion protection. Same accounts as today (migrator, app,
  backup). Upgrade to 9.7 once RDS makes it generally available; the test suite runs on both
  versions so the upgrade is pre-tested. Keycloak gets its own database on the same instance.
- **Redis**: Amazon ElastiCache (Valkey/Redis OSS engine) with TLS and AUTH, one replica.
- **Email**: Amazon SES (SMTP interface) with the `occplsoftech.com` domain identity (DKIM, SPF,
  DMARC); sender `no-reply@occplsoftech.com`.
- **Secrets**: AWS Secrets Manager; instances read them at deploy time through their IAM role.
- **Images**: Amazon ECR (scan on push). GitHub Actions pushes and deploys through OIDC federation
  (no long-lived AWS keys); deployment = SSM Run Command, one instance at a time (deregister from the
  load balancer, pull the new image digest, start, wait for readiness, re-register).
- **Monitoring**: container logs to CloudWatch Logs; CloudWatch agent scrapes
  `/actuator/prometheus`; CloudWatch alarms implement the alerts in DEPLOYMENT.md §6.
- **Infrastructure as code**: Terraform (`infra/aws/`), state in S3 with locking; staging is the
  same code with smaller sizes (one instance, single-AZ database).

## Consequences
+ Managed database, Redis, email, certificates and secrets: the team operates only the two
  instances and the containers. RDS backups cover RPO/RTO; the MySQL Shell scripts
  (`infra/backup/`) remain for logical exports and cross-region copies.
+ Same Compose files as development; no new orchestration to learn.
− The database runs 8.4 instead of the tested 9.7: mitigated by running the full test suite on
  8.4 (default) and 9.7 in CI. ADR-0007 ("latest versions") gets this exception.
− Instances must be patched (unattended security upgrades, replaced from a refreshed image
  monthly); scaling beyond a few instances or per-service autoscaling would justify ECS later.
