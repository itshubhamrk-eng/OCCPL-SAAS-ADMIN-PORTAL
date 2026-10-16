# Operations runbook: single VPS (ADR-0012)

Commands marked **[Mac]** run on your computer, **[server]** over SSH as user `occpl`
(`ssh -i ~/.ssh/occpl_vps occpl@<server-ip>`), unless stated otherwise.

## 1. Before you start

| You need | Notes |
|---|---|
| A VPS | Ubuntu 24.04, **4 vCPU, 8 GB RAM or more, 80 GB+ disk**, IPv4 address, in an Indian data centre if offered (latency, data residency). Add your SSH public key when ordering. |
| The domain's DNS in Route 53 | Done (zone `occplsoftech.com`). |
| A GitHub admin login | For the environment, variable and secrets in §3. |
| An SMTP mailbox | `tech@occplsoftech.com` (Hostinger mail). |

Generate a key pair for the server: **[Mac]** `ssh-keygen -t ed25519 -f ~/.ssh/occpl_vps -C occpl-deploy`
(use it for the VPS and as the deploy key in §3; `~/.ssh/occpl_vps.pub` is the public half).

## 2. DNS: point the three hosts at the server

Route 53 → hosted zone `occplsoftech.com` → **Create record**, three **A** records, TTL 300, value =
the server's IPv4 address:

| Record name | Type | Value |
|---|---|---|
| `admin` | A | `<server-ip>` |
| `api` | A | `<server-ip>` |
| `auth` | A | `<server-ip>` |

Certificates are requested on first start, so these must resolve to the server (check with
`https://dnschecker.org`, type A) before the first deploy.

## 3. GitHub

Repository → Settings → Environments → **production** (create it; deployment branch `main`; add
yourself as **required reviewer**). In it:

| Kind | Name | Value |
|---|---|---|
| Variable | `VPS_HOST` | the server's IPv4 address |
| Secret | `VPS_SSH_KEY` | the content of `~/.ssh/occpl_vps` (private key) |
| Secret | `VPS_KNOWN_HOSTS` | output of `ssh-keyscan -t ed25519 <server-ip>` |

Also check Settings → Actions → General → *Workflow permissions* allows the workflow to publish
packages (the workflow declares `packages: write`).

## 4. Prepare the server (once)

1. **[Mac]** Copy the bundle: from the repository root,
   ```bash
   ssh -i ~/.ssh/occpl_vps root@<server-ip> 'mkdir -p /opt/occpl/backup'
   scp -i ~/.ssh/occpl_vps -r infra/vps/. root@<server-ip>:/opt/occpl/
   scp -i ~/.ssh/occpl_vps -r infra/backup/. root@<server-ip>:/opt/occpl/backup/
   ```
   (Hostinger's VPS login is `root` with your key. Use the panel's console if SSH is not up yet.)
2. **[server as root]** `bash /opt/occpl/bin/install.sh`. It installs Docker, opens only ports
   22/80/443, enables fail2ban and automatic security updates, creates the 2 GB swap, creates the
   `occpl` user (same SSH key) and the cron jobs.
3. **[server as occpl]** `cd /opt/occpl && bin/init-env.sh`. It asks for the domain, certificate
   email, **the public IP addresses allowed to open the Keycloak console** (your office/home IP,
   `curl -s https://checkip.amazonaws.com` on your computer, with `/32`), the SMTP mailbox
   and password, and the first Super Admin's email. It writes `/opt/occpl/.env` (mode 600).
   **Copy `.env` to a password manager**: it holds every password.
4. SMTP mode: Hostinger usually accepts port 465 (SSL, the default here); if the mailbox only
   works on 587, answer `587` (STARTTLS).

## 5. First deploy and first Super Admin

1. Merge to `main` (or Actions → *Release* → Run workflow is only for an existing build). CI runs,
   then *Release* publishes the images and waits for your approval: **Review deployments →
   production → Approve**. It copies the bundle, runs `bin/deploy.sh` (first start pulls images and
   migrates the database: about 2 minutes) and the smoke test.
2. Open `https://auth.occplsoftech.com/admin/` **from an allowed IP**. User `bootstrap-admin`,
   password = `KEYCLOAK_ADMIN_PASSWORD` in `/opt/occpl/.env`. In realm **occpl**: Users → Add user, the
   email you gave as the first Super Admin (mark *Email verified*), Credentials → set a password (or
   *Required user actions → Update password* and send the email).
3. The user opens `https://admin.occplsoftech.com`, signs in and sets up an authenticator app; Core
   then grants SUPER_ADMIN (audited).
4. **[server]** Clear `BOOTSTRAP_SUPER_ADMIN_EMAIL=` in `.env`, then `docker compose up -d core-platform`.
5. Create a personal admin in the Keycloak *master* realm and disable `bootstrap-admin`.

## 6. Backups, recovery, monitoring

**Off-host backups.** In the AWS console/CLI on your Mac: `cd infra/aws/shared && terraform apply`
creates the bucket and a write-only user. Then `terraform output backups_bucket`,
`terraform output vps_backup_access_key_id` and `terraform output -raw vps_backup_secret_access_key`.
**[server as occpl]** `aws configure` (region `ap-south-1`, the two values), then add
`BACKUP_S3_URI=s3://<bucket>/vps` to `.env` and run `bin/backup.sh full`; check with
`aws s3 ls s3://<bucket>/vps/`.

Cron (installed by `install.sh`): full backup 02:00 IST, binary-log increment every 5 minutes, health
check every 5 minutes; 7 days are kept locally, 35 in S3.

**Alerts.** Create a free check at healthchecks.io (period 10 min), put its ping URL in `.env`
as `HEARTBEAT_URL=...`. It emails you when the server stops reporting or a service is unhealthy. Add
an external uptime monitor (e.g. UptimeRobot) on `https://admin.occplsoftech.com/healthz`. Set
`BACKUP_PING_URL` to a second healthchecks.io check (period 1 day) to be told about a missed
nightly backup.

**Restore (new or damaged server; tested, 6–8 s for the drill database).**
1. Prepare a server (§4 steps 1–3, restore the saved `.env` instead of running `init-env.sh`).
2. **[server]** Fetch the backups: `aws s3 sync s3://<bucket>/vps /var/backups/occpl`.
3. Start only the database: `cd /opt/occpl && docker compose up -d mysql` (wait until healthy).
4. Restore (latest point, or add `STOP_BEFORE=<gtid>` to stop before a mistake):
   ```bash
   MYSQL_HOST=127.0.0.1 MYSQL_USER=root MYSQL_PASSWORD=$(sed -n 's/^MYSQL_ROOT_PASSWORD=//p' .env) \
     backup/restore.sh /var/backups/occpl
   ```
5. Start the rest: `bin/deploy.sh <the release that was running>` (see `RELEASE=` in `.env`), then take a
   full backup: `bin/backup.sh full`.

## 7. Day-to-day

- **Deploy:** merge to `main`, approve in GitHub. **Roll back:** Actions → *Release* → Run workflow with
  the full SHA of an earlier commit (the images are kept in the registry), approve. A failed deploy
  rolls back by itself.
- **Logs:** `docker compose logs -f core-platform` (also `keycloak`, `caddy`, `mysql`); deploy log
  `/var/log/occpl-deploy.log`; health problems `/var/log/occpl-health.log`.
- **Status:** `docker compose ps`; disk: `df -h /` (backups and images grow it).
- **OS updates:** security updates install automatically; every few weeks run
  `sudo apt-get update && sudo apt-get upgrade -y && sudo reboot` (about a minute of downtime; the
  containers restart by themselves).
- **Secrets:** edit `/opt/occpl/.env`, then `docker compose up -d`. Database passwords also have to be
  changed inside MySQL (`ALTER USER ...`); Keycloak client secrets in the realm (Clients → Credentials).
- **Razorpay:** put the keys into `.env`, `docker compose up -d core-platform`; set the webhook URL
  to `https://api.occplsoftech.com/api/v1/webhooks/payments/razorpay`.
- **Realm changes after the first start:** the realm import only runs when the realm does not exist:
  change settings in the Keycloak console.
- **Resize:** upgrade the VPS plan in the provider's panel (a reboot), then raise
  `CORE_MEM_LIMIT`, `KEYCLOAK_MEM_LIMIT`, `MYSQL_MEM_LIMIT` and `MYSQL_BUFFER_POOL` in `.env`.

## 8. Moving to AWS later

`infra/aws` is ready (ADR-0011). Restore the latest backup into RDS with `backup/restore.sh`
(RUNBOOK of `infra/aws` §3), point DNS at the load balancer, switch the pipeline to
`deploy-aws.yml`.

## 9. Development/test server on EC2 (D-17)

The same stack runs on one EC2 instance at `admin.dev.occplsoftech.com`, `api.dev…`, `auth.dev…`
(about $85 a month while running; **stop the instance in the console when not in use**: compute stops
billing, the disk and the public address still do). It is the place to try a release before approving
the production deploy. Outgoing email is real (the same SMTP mailbox): use test addresses.

1. **[Mac]** `cd infra/aws/dev && cp terraform.tfvars.example terraform.tfvars`; fill in your public
   key (`cat ~/.ssh/occpl_vps.pub`) and your public IP (`curl -s https://checkip.amazonaws.com`,
   as `x.x.x.x/32`). `terraform init && terraform apply` (needs the bootstrap stack; creates the
   instance, a fixed public address, firewall rules, an S3-backup role and the three DNS records).
   Optional but recommended: apply `infra/aws/shared` first (backup bucket).
2. Copy the bundle (the login user on Ubuntu is `ubuntu`):
   ```bash
   scp -i ~/.ssh/occpl_vps -r infra/vps ubuntu@<ip>:/tmp/occpl
   scp -i ~/.ssh/occpl_vps -r infra/backup ubuntu@<ip>:/tmp/occpl/backup
   ssh -i ~/.ssh/occpl_vps ubuntu@<ip> 'sudo mkdir -p /opt/occpl && sudo cp -r /tmp/occpl/. /opt/occpl/ && sudo bash /opt/occpl/bin/install.sh'
   ```
3. `ssh -i ~/.ssh/occpl_vps occpl@<ip>`, then `cd /opt/occpl && bin/init-env.sh` with domain
   `dev.occplsoftech.com` (the `domain_for_env` output) and your IP as ADMIN_IPS. For backups add
   `BACKUP_S3_URI=<backup_s3_uri output>` to `.env` (the instance role grants access; no `aws configure`).
4. GitHub environment **dev** (no reviewers needed): variables `VPS_HOST` = the public address and
   `HOST_SUFFIX` = `dev.`; secrets `VPS_SSH_KEY` and `VPS_KNOWN_HOSTS` as in §3. After a merge to `main`
   the *Release* workflow deploys to dev automatically and smoke-tests it; production only follows when
   the repository variable `DEPLOY_PRODUCTION` is `true` and the reviewers approve.
5. The first Super Admin and the rest work as in §5, with the `.dev.` hostnames.
6. The GitHub runners connect from changing addresses: the security group must allow SSH (22) from anywhere
   (key-only login, fail2ban), plus 80 and 443. If you create the instance by hand in the console instead of
   Terraform: Ubuntu 24.04, `t3.large`, 80 GiB gp3, Elastic IP, credit specification *Standard*, IMDSv2 only; log in
   as `ubuntu` with the key pair you created, add `~/.ssh/occpl_vps.pub` to `~/.ssh/authorized_keys`, then continue at step 2.
7. **Sign-in page theme on an existing realm.** The realm import only runs on the very first start, so a realm created
   before the `occpl` theme existed keeps the stock Keycloak look. Set it once on the server (after the release that
   contains the theme is deployed):
   ```bash
   cd /opt/occpl && set -a && . ./.env && set +a
   K="docker exec -i $(docker ps --format '{{.Names}}' | grep keycloak | head -1) /opt/keycloak/bin/kcadm.sh"
   $K config credentials --server http://localhost:8080 --realm master --user bootstrap-admin --password "$KEYCLOAK_ADMIN_PASSWORD"
   $K update realms/occpl -s loginTheme=occpl
   ```
   New installs get it from the generated production realm.
8. **One-time code off on dev only (optional).** Add `MFA_PLATFORM_REQUIRED=false` to `/opt/occpl/.env`, then
   `docker compose up -d core-platform`. Core then gives platform staff their permissions without the authenticator
   code. To stop Keycloak asking for the code, delete the user's *otp* credential (Keycloak admin console, realm
   occpl, Users, Credentials). Do not do this on production: MFA for platform roles is a security requirement (D-2).
9. **Admin console open to every address on dev (optional, dev only).** Set `ADMIN_IPS=0.0.0.0/0 ::/0` in `/opt/occpl/.env` and
   `docker compose up -d --force-recreate caddy`; then add the variable `ALLOW_PUBLIC_ADMIN_CONSOLE` = `true` to the GitHub
   environment **dev** (Settings, Environments, dev), otherwise the smoke test reports the open console as a failure. Anyone can then reach
   the Keycloak administrator sign-in page, so keep `KEYCLOAK_ADMIN_PASSWORD` long and random. Never on production.
