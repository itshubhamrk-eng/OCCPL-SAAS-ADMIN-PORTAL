# AWS infrastructure (ADR-0011)

Region **ap-south-1 (Mumbai)**, domain **occplsoftech.com** (D-12), MySQL on **RDS 8.4 LTS**
(D-14), app containers on **EC2 with Docker Compose** (D-13). One AWS environment,
**production**; releases are tried on the local Docker Compose stack before approval (D-15).
Operations: [RUNBOOK.md](RUNBOOK.md).

```
infra/aws/
├── bootstrap/            S3 bucket for the Terraform state (applied once, local state)
├── shared/               ECR repositories, artifacts bucket, GitHub OIDC + build role, SES
├── modules/platform/     one environment: VPC, ALB + WAF + ACM, EC2 Auto Scaling group,
│                         RDS, ElastiCache (Valkey), secrets, alarms, backups, deploy role
├── envs/production/      module call with production sizes (*.occplsoftech.com)
├── compose/              release bundle copied to every instance (/opt/occpl):
│   ├── docker-compose.yml      core-platform, admin-portal, keycloak (images from ECR)
│   ├── bin/deploy.sh           render config → pull → up → wait until ready
│   ├── bin/render-env.sh       SSM config + Secrets Manager → /etc/occpl/*.env (mode 600)
│   ├── bin/bootstrap-db.sh     schemas and least-privilege DB accounts (TLS required)
│   ├── cloudwatch/             agent config: disk/memory metrics, logs, Prometheus → EMF
│   └── keycloak/               production Keycloak image (optimized build + realm import)
└── scripts/              run by the Deploy workflow or an operator:
    ├── rolling-deploy.sh       one instance at a time through the load balancer, auto-rollback
    ├── smoke-test.sh           public checks through ALB/WAF/TLS after a deploy
    └── bootstrap-database.sh   runs bootstrap-db.sh on an instance through SSM
```

## Topology

```
Internet → Route 53 → ALB (TLS 1.2+/1.3, ACM; WAF: rate limit, IP reputation, common rules)
             ├── admin. →  :8088 admin-portal (nginx, static Angular)
             ├── api.   →  :8080 core-platform        (health :8081, not public)
             └── auth.  →  :8180 keycloak             (/admin only from admin_cidrs)
EC2 Auto Scaling group (app subnets, no public IPs, SSM instead of SSH, IMDSv2)
             ├── RDS MySQL 8.4 (data subnets, TLS-only accounts, Multi-AZ)
             └── ElastiCache Valkey 8 (TLS + auth token; rate limiting, fail-open)
```

Core reaches Keycloak on the same host (`host.docker.internal:8180`) for JWKS, the admin API and
client credentials; the token issuer stays the public `https://auth.occplsoftech.com`.
Keycloak instances form one cluster (JGroups jdbc-ping through the shared database).

| | Production |
|---|---|
| Instances | 2 × m7i.large (two AZs) |
| NAT gateways | 2 |
| RDS | db.m7g.large, Multi-AZ, 35-day PITR, copy to ap-south-2 |
| ElastiCache | 2 nodes, automatic failover |
| Logs retained | 365 days |

The module is environment-neutral: a staging environment can be added later (RUNBOOK.md §6).

## Working with the code

```bash
terraform fmt -recursive infra/aws
terraform -chdir=infra/aws/envs/production init -backend=false && terraform -chdir=infra/aws/envs/production validate
python3 infra/aws/compose/keycloak/make-production-realm.py          # after changing the dev realm
```

CI (`infra` job) runs fmt, validate, the realm check, shellcheck and a compose config check on
every PR. Plans and applies are run by an operator (RUNBOOK.md §1); there is no automatic
`terraform apply`.

Secrets never enter Git: generated passwords live in Secrets Manager (`occpl/production/app`, also
in the encrypted state bucket), Razorpay keys are entered by an operator, SES SMTP credentials
are in `occpl/shared/ses-smtp`, and the RDS master password is managed by RDS.
