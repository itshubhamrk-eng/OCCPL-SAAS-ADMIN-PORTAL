# Operations runbook (AWS, ADR-0011)

Everything below runs from a workstation with administrator credentials for the AWS account
(`aws sso login` or similar), `AWS_REGION=ap-south-1`, Terraform 1.16.x, and a checkout of this
repository. There is one AWS environment, `production`; releases are tested beforehand by CI and on
the local Docker Compose stack (D-15). Values such as the Auto Scaling group name come from
`terraform -chdir=infra/aws/envs/production output`.

```bash
export AWS_REGION=ap-south-1 OCCPL_ENV=production
export ASG_NAME=$(terraform -chdir=infra/aws/envs/$OCCPL_ENV output -raw asg_name)
export TARGET_GROUP_ARNS="$(terraform -chdir=infra/aws/envs/$OCCPL_ENV output -json target_group_arns | jq -r 'values|join(" ")')"
export ARTIFACTS_BUCKET=$(terraform -chdir=infra/aws/shared output -raw artifacts_bucket)
```

## 1. First-time setup (once)

Prerequisites: an AWS account; the public Route 53 hosted zone `occplsoftech.com` in that
account (the registrar's name servers pointing at it); a GitHub admin on the repository.

1. **State bucket.** `terraform -chdir=infra/aws/bootstrap init && terraform -chdir=infra/aws/bootstrap apply`.
2. **Shared stack** (ECR, artifacts bucket, GitHub OIDC, SES): `init` + `apply` in `infra/aws/shared`.
   Commit the generated `.terraform.lock.hcl` files of every stack.
3. **SES production access.** New accounts are in the SES sandbox (they only send to verified
   addresses). Request production access for ap-south-1 in the SES console (use case:
   transactional mail: invitations, password reset, MFA, billing notices). The DKIM records were
   created by step 2; the identity shows *Verified* within about an hour.
4. **Production stack.** Copy `envs/production/terraform.tfvars.example` to `terraform.tfvars`
   (git-ignored), fill it in, then `init` + `apply` in `infra/aws/envs/production`. It takes about 25
   minutes (RDS Multi-AZ, ElastiCache, certificate validation). Confirm the SNS email subscription
   sent to `alarm_email`. The instances boot, find release `none` and wait.
5. **GitHub.**
   - Repository variables: `AWS_BUILD_ROLE_ARN` (shared output `github_build_role_arn`),
     `ECR_REGISTRY` (`ecr_registry`), `ARTIFACTS_BUCKET` (`artifacts_bucket`).
   - Environment `production`, limited to the `main` branch, with required reviewers and the
     variables from the output `github_environment_variables`.
   - The IAM trust policies match `repo:occplsoftech/OCCPL-SAAS-ADMIN-PORTAL:…` exactly
     (case-sensitive). If the repository is renamed or transferred, update `github_repository`.
6. **Database accounts.** `infra/aws/scripts/bootstrap-database.sh` creates the `occpl_core` and
   `keycloak` schemas and the TLS-only accounts `occpl_migrator`, `occpl_app`, `occpl_backup` and
   `keycloak` with the passwords from `occpl/production/app`. It is idempotent.
7. **First deploy.** Push to `main` (or run the *Deploy* workflow). The first instance runs the
   Flyway migrations, and Keycloak imports the `occpl` realm on its first start.
8. **Billing (optional).** Put the Razorpay keys into the secret `occpl/production/razorpay`:
   `{"key_id":"…","key_secret":"…","webhook_secret":"…"}`. Then redeploy the current release (§2)
   so the instances pick them up. In the Razorpay dashboard, set the webhook URL to
   `https://api.occplsoftech.com/api/v1/webhooks/payments/razorpay`. Empty keys mean
   billing is disabled.
9. **First Super Admin.** `bootstrap_super_admin_email` is set in tfvars, and the realm already
   uses SES for mail.
   1. Open `https://auth.occplsoftech.com/admin/` from an `admin_cidrs` address. User:
      `bootstrap-admin`; password: `keycloak_admin_password` in `occpl/production/app`.
   2. In realm `occpl`, create the user with that email (verified) and send an *Update password*
      email.
   3. The user signs in to the portal and enrols an authenticator app. Core then grants
      SUPER_ADMIN.
   4. Clear `bootstrap_super_admin_email`, apply, and redeploy.
   5. Then create a personal Keycloak admin in the `master` realm and disable `bootstrap-admin`.

## 2. Deploy, redeploy, roll back

- **Normal path.** A merge to `main` runs CI. *Deploy* then publishes the images tagged with the
  commit SHA and waits for a reviewer to approve the `production` environment; then the rollout
  and a smoke test run. Try a release on the local compose stack before approving.
- **Redeploy or roll back.** In GitHub: Actions → *Deploy* → *Run workflow*, with the release
  (the full SHA of an earlier release; ECR keeps the last 100), then approve it. The same thing
  from a workstation: `infra/aws/scripts/rolling-deploy.sh <sha> && infra/scripts/smoke-test.sh`.
- **How a rollout works.** One instance at a time: leave the load balancer, deploy through SSM,
  wait until Keycloak, Core and the portal are ready, then rejoin. If an instance fails, every
  instance that was already updated goes back to the previous release and the run fails.
- **Downtime.** None: one of the two instances keeps serving throughout. The first deploy and
  any release with a risky migration are best done outside business hours, since there is no
  staging environment to try them on AWS first.
- **Migrations.** Flyway runs on the first instance under its lock. Migrations are
  expand/contract (DEPLOYMENT.md §4), so the previous release runs on the new schema. A rollback
  never rolls the database back.
- **Inspect.** Deploy log: `/var/log/occpl-deploy.log` on the instance, and CloudWatch log group
  `/occpl/production/deploy`. Container logs: `/occpl/production/core-platform`, `…/keycloak` and
  `…/admin-portal`. Shell: `aws ssm start-session --target <instance-id>`, then
  `sudo docker compose -p occpl ps`.

## 3. Database recovery (RDS)

Backups: automated RDS snapshots plus transaction logs (point-in-time restore to any second in
the retention window: 35 days). AWS Backup also takes a daily copy at
02:00 IST; in production that copy goes to ap-south-2 (Hyderabad). Failures alert through SNS.

**Undo a mistake (point-in-time restore).**
1. Find the time just before the bad change (audit log, CloudTrail, application logs).
2. `aws rds restore-db-instance-to-point-in-time --source-db-instance-identifier occpl-production --target-db-instance-identifier occpl-production-pitr --restore-time <UTC ISO time> --db-subnet-group-name occpl-production --vpc-security-group-ids <db sg> --db-parameter-group-name occpl-production-mysql84 --multi-az --no-publicly-accessible`
3. Either copy the affected rows back from the restored instance (most cases), or switch over:
   1. rename the old instance and give the restored one its name;
   2. run `terraform apply` to adopt the restored instance (`terraform import` if needed);
   3. run `bootstrap-database.sh` and redeploy the current release.
4. Delete the temporary instance when you are done.

**Region loss (production).**
1. Restore the latest recovery point from the ap-south-2 vault into a new VPC.
2. Apply the environment stack in ap-south-2: copy `envs/production`, set the region and
   CIDR, and set `cross_region_backup = false`.
3. Run `bootstrap-database.sh` and deploy the current release.
4. Move DNS: the stack creates the Route 53 alias records.
5. Targets: RPO is at most 24 hours from the copies. The RTO depends on that apply, about 1–2 hours.

The Keycloak database lives on the same RDS instance and is restored with it.

## 4. Incidents

| Alarm | First steps |
|---|---|
| `api-5xx-rate`, `api-latency-p95` | Core logs; RDS Performance Insights; `db-pool-pending`; recent deploys → roll back (§2) |
| `<service>-down`, `<service>-unhealthy` | `docker compose ps` / logs on the instance; ASG activity; a bad release → roll back |
| `instances-missing` | ASG activity history (capacity, AMI, user data in `/var/log/occpl-bootstrap.log`) |
| `disk-80` | `docker system df`; `docker image prune -a`; journal size |
| `db-cpu` / `db-free-storage` / `db-freeable-memory` / `cache-cpu` | Performance Insights top SQL; storage autoscaling raises the limit up to `max_allocated_storage` |
| `rate-limiter-down` | Core cannot reach ElastiCache: rate limiting is off (fail-open). Check the cache cluster and `REDIS_URL` |
| `provisioning-failed` | Platform → Provisioning jobs in the portal; product service health; retry from the portal |
| `webhook-rejections` | Webhook secret mismatch with the Razorpay dashboard, or attack traffic (WAF logs) |
| `certificate-expiry` | ACM renews automatically while the DNS validation records exist; check them |
| Backup job failed | AWS Backup console → job details; a manual snapshot `aws rds create-db-snapshot` |

## 5. Secret rotation

| Secret | Rotation |
|---|---|
| Database passwords (`db_*_password`) | `terraform apply -replace='module.platform.random_password.app["db_app"]'` (etc.) → `bootstrap-database.sh` (ALTER USER) → redeploy the current release. Short window of failed connections on running instances until they are redeployed. |
| `keycloak_admin_client_secret`, `provisioning_client_secret` | Replace in Terraform, set the new value on the client in Keycloak (realm `occpl` → Clients → Credentials), redeploy. |
| `redis_auth_token` | ElastiCache supports a rotation with two valid tokens (`ROTATE`, then `SET`); apply with the new token and redeploy before removing the old one. |
| SES SMTP | `terraform apply -replace=aws_iam_access_key.ses_smtp` in `shared`, then redeploy both environments. |
| Razorpay | New keys in the Razorpay dashboard → update `occpl/production/razorpay` → redeploy. |
| RDS master | Managed by RDS (Secrets Manager rotation, 7 days by default); used only by `bootstrap-database.sh`. |

## 6. Routine maintenance

- **OS patches.** Security updates install automatically, but reboots are left to us. For
  kernels and a fresh base image, start an instance refresh:
  `aws autoscaling start-instance-refresh --auto-scaling-group-name $ASG_NAME`. New instances boot
  the current release, and the refresh keeps capacity (production) while it replaces hosts.
- **Instance size or count.** Change `instance_type` or `instance_count` in `envs/production/main.tf`,
  apply, then refresh.
- **MySQL minor versions** are applied by RDS in the maintenance window. For major versions
  (9.x), see ADR-0011: CI already runs the suite on 9.7.
- **Realm changes after the first import.** The import only runs while the realm does not exist.
  Change `infra/docker/keycloak/occpl-realm.json`, run `make-production-realm.py`, and commit
  (this covers new environments). Then apply the same change in the admin console of each
  existing environment, or with `kcadm.sh` in an SSM session.
- **Cost.** The NAT gateways, RDS Multi-AZ and the instances are most of the bill.
- **Adding an AWS staging environment later.** Copy `envs/production` to `envs/staging`, change
  the state key, `env`, `host_suffix = "staging."`, the VPC CIDR and the sizes, apply, add a GitHub
  environment `staging` and a staging job before production in `deploy.yml`.
