# OCCPL Innovations — Multi-Product SaaS Platform

Status: **Phase 10 (Deployment) code complete**: single-VPS stack (`infra/vps`, ADR-0012) is the launch target, AWS stack ready as the later option — go-live needs the VPS and the first deploy (V-3). Before live payments: D-11 (GST) and the Razorpay smoke test (R-5). Before production sizing: load test on the production environment before launch (H-5) and confirmed SLOs (D-6).
Phase 1 decisions were answered on 2026-10-13 (see §5).

## 1. Business objective

OCCPL Innovations sells several independent software products to companies through one
central SaaS platform. The **Core Platform** owns identity mapping, companies, memberships,
roles/permissions, the product catalogue, plans, subscriptions, entitlements, provisioning,
billing integration, audit and notifications. It contains **no product-specific business
logic**. Each product is an independently deployed service that owns its own data.

### Actors

| Actor | Scope | Typical goals |
|---|---|---|
| Super Admin (OCCPL staff) | Platform | Create companies, register products, define plans, assign subscriptions, monitor provisioning/audit |
| Platform Support / Finance (future OCCPL staff roles) | Platform, restricted | Read-only support, billing operations |
| Company Admin | One company (per membership) | Manage company profile, users, roles, view subscriptions/usage/billing, launch products |
| Company Manager | One company | Manage users within delegated permissions |
| Company User | One company | Launch entitled products |
| Product Service | Machine identity | Receive provisioning calls, check entitlements, report usage |

### In scope (Core Platform)

Company lifecycle; user & membership management with invitations; role/permission
configuration (platform- and company-scoped); product catalogue with versioned configuration;
plans with features and limits; company subscriptions with explicit state machine;
entitlement calculation and server-side limit enforcement; durable provisioning to product
services; payment-gateway integration (webhooks, idempotency); audit logging; notifications
(email first); platform reporting; Super Admin and Company Admin portals (one Angular app).

### Out of scope (Core Platform)

Product business logic and data; password storage/token issuance (delegated to the OIDC IdP);
a general-purpose billing/accounting ledger (we record invoices/transactions and integrate a
gateway); Kubernetes, Kafka, sharding (until measured need — see ARCHITECTURE.md §9).

## 2. Scale targets (design targets, not guarantees)

10,000+ companies, 1,000,000+ users, multiple products/company, users in multiple companies.
These must be validated by load tests with acceptance criteria defined in BACKLOG.md (H-3)
before launch. No hard-coded limits on companies, users or products.

Provisional SLOs to be confirmed by the business (see Decision D-6):

| Metric | Provisional target |
|---|---|
| Availability (Core API, monthly) | 99.9% |
| p95 latency, company-scoped reads | < 300 ms |
| p95 latency, entitlement check (cached) | < 50 ms |
| p95 latency, admin writes | < 800 ms |
| Error rate (5xx) under nominal load | < 0.1% |
| RPO / RTO | 15 min / 4 h |

## 3. Current repository assessment (Phase 1 discovery)

Inspected on 2026-10-13, branch `claude/affectionate-bardeen-8xrm9g`:

- Contents: a single `README.md` containing only the title. One commit ("Initial commit").
- No backend, frontend, build files, migrations, CI or infrastructure exist. Nothing to preserve
  or migrate; this is a greenfield project.
- Tooling available in the development container: OpenJDK 21.0.12, Maven (`/opt/maven`),
  Node.js 22.22, Docker 29.8. Versions checked against public registries on 2026-10-13:
  Angular `latest` = 22.2.x (21.x and 20.x on LTS); Spring Boot newest GA = 4.1.x;
  Spring Modulith 1.4.x (Boot 3.5 line); Keycloak 26.x.

### Technology versions (decision: "use the latest version")

Checked against the public registries on 2026-10-13. Where a product has both a newest feature
release and a newer-than-previous LTS line, the **latest LTS** is used for production stability
(see ADR-0007); moving to the feature release is a one-line change if OCCPL prefers it.

| Area | Version in use | Newest available | Note |
|---|---|---|---|
| Java | **25 LTS** (Temurin 25.0.4) | 27 (non-LTS, 6-month support) | Supersedes the original "Java 21" mandate |
| Spring Boot | **4.1.1** (Spring Framework 7, Security 7, Hibernate 7.4, Jackson 3.1, Flyway 12.4) | 4.1.1 GA | Supersedes the original "3.x" mandate |
| Spring Modulith | 2.1.1 | — | Module boundary verification |
| springdoc-openapi | 3.1.1 | — | |
| Angular | **22.2** (TypeScript 6.0, Vitest 5, zoneless) | 22.2.2 | |
| Node.js (build only) | 24 LTS | 26 | Angular 22 requires ≥ 22.22.3 / 24.15 |
| MySQL | **8.4 LTS in production** (Amazon RDS, D-14); 9.7 LTS tested in CI | 26.7 (innovation track) | RDS offers 9.7 only in preview; the full suite passes on 8.4 and 9.7; Flyway is verified for 8.4 |
| Keycloak | **26.8.1** | 26.8.1 | |
| Nginx | 1.30 (stable, unprivileged image) | 1.31 mainline | |
| Redis | not yet added | 8.10 | Added with rate limiting / caching in Phase 3 |
| Testcontainers | 2.0.x (Boot-managed) | 2.0.5 | |

## 4. Explicit assumptions

| # | Assumption |
|---|---|
| A-1 | A single OCCPL-run IdP realm holds all human users; companies do not bring their own IdP initially (enterprise SSO/federation is a later backlog item). |
| A-2 | Users are identified globally by email at invitation time; the stable key is the platform `user.id` (UUID) mapped to the IdP subject. |
| A-3 | One subscription per (company, plan). A company may hold several concurrent subscriptions (e.g. one per product, or a bundle plan covering several products). |
| A-4 | Currency is per plan price (ISO-4217). Initial currency INR; multi-currency supported in schema. Invoices are created by the payment gateway (D-4); how GST is applied is open (D-11). |
| A-5 | Upgrades take effect immediately with proration computed by the billing module; downgrades take effect at the next renewal (see DATABASE.md §6). |
| A-6 | Seat limits count ACTIVE + INVITED memberships with access to the product. |
| A-7 | Product services are trusted OCCPL-built services reachable over a private network or mTLS-protected public endpoint. |
| A-8 | English UI only at launch; i18n-ready structure in Angular. |
| A-9 | Data residency: single region (India) at launch. |
| A-10 | Email is the only notification channel at launch (SMTP / transactional email provider). |

## 5. Business decisions

| # | Decision | Status |
|---|---|---|
| D-1 | Versions | **Decided:** latest versions — see §3 and ADR-0007 |
| D-2 | Login provider | **Decided:** the secure option — self-hosted Keycloak (data stays in OCCPL infrastructure), MFA enforced for platform roles, brute-force protection, strong password policy (ADR-0002, SECURITY.md) |
| D-3 | Payment gateway | **Decided:** Razorpay, behind a gateway port |
| D-4 | Invoicing / GST | **Decided:** invoices generated by the gateway (Razorpay); Core stores references. Implemented 2026-10-14 (Razorpay Invoices API, webhooks). |
| D-5 | Grace period, retention after cancel/expiry | **Decided (2026-10-13):** 7-day grace before suspension; data kept 90 days after cancellation/expiry; suspended subscriptions expire after 90 days. All configurable. |
| D-6 | Confirm SLOs in §2 | Open — the provisional SLOs were met in the Phase 9 load test on a development VM up to 210 req/s (load-tests/RESULTS.md); confirm targets and expected peak load before H-5 / production sizing |
| D-7 | Company Admin self-serve purchases? | Open — default: Super Admin assigns plans |
| D-8 | Self-registration vs invitation only | **Applied default:** invitation only (Phase 4) |
| D-9 | Product launch model (subdomains + SSO vs path-based) | **Applied default (Phase 7):** both are supported without a code change — each product has its own `appUrl` (own host or subdomain) plus a `launch.path` template (`{companyId}`, `{companyCode}`); sign-in is SSO through Keycloak. Revisit if per-company subdomains are wanted. |
| D-10 | Personal-data retention/export obligations (DPDP Act 2023, contracts) | Open — needed before launch (BACKLOG H-7) |
| D-12 | Production domain | **Decided (2026-10-14):** `occplsoftech.com` — hostnames in DEPLOYMENT.md §2 (admin., api., auth., products as subdomains; `*.staging.` reserved, unused per D-15). Hosting: AWS (D-13). |
| D-13 | Hosting | **Decided (2026-10-14): AWS**, region `ap-south-1` (Mumbai), EC2 instances with Docker Compose behind an Application Load Balancer (ADR-0011). |
| D-14 | Production database | **Decided (2026-10-14): Amazon RDS for MySQL 8.4 LTS** (RDS has 9.7 only in preview). Tests run on 8.4 by default and on 9.7 in CI; upgrade when RDS 9.7 is generally available. |
| D-15 | Staging environment | **Decided (2026-10-14): none on AWS (superseded by D-17: a dev/test EC2 server).** Releases are tried on the local Docker Compose stack, then approved for production; first deploy, rollback and restore drills run on production before launch (ADR-0011 amendment). |
| D-16 | Launch hosting | **Decided (2026-10-15): one VPS** (Docker Compose, Caddy, MySQL 8.4 and Redis in containers) instead of the AWS stack; AWS design kept ready (ADR-0012). AWS keeps DNS (Route 53), the off-host backup bucket and (later) SES. |
| D-17 | Development/test environment | **Decided (2026-10-15): one EC2 instance** (`infra/aws/dev`, `*.dev.occplsoftech.com`) runs the same stack; releases deploy there first, production (Hostinger VPS, later) after approval. Replaces D-15. |
| D-18 | Portal visual design | **Decided (2026-10-15): "Sunrise Mesh Glass"** (crimson `#D61F45`, indigo `#4338CA`, light grey, glass cards, crimson side menu), chosen from sample boards; all colours live in design tokens so the brand guide can replace them. |
| D-11 | GST on invoices: are plan prices tax-inclusive or exclusive, which GST rate/HSN-SAC code, and is the company's GSTIN printed on invoices? | **Open — needed before live payments.** Today the plan price is sent to Razorpay as the line amount with no tax fields; the company's tax id is stored but not sent. |

## 6. Risks

| # | Risk | Mitigation |
|---|---|---|
| R-1 | Flyway 12.4 is not verified by its vendor on MySQL 9.7 | Production uses MySQL 8.4 (verified, no warning); 9.7 stays in the CI matrix until RDS and Flyway support it |
| R-2 | Spring Boot 4 / Jackson 3 ecosystem is newer; some third-party libraries may lag | Prefer Boot-managed dependencies; check compatibility before adding libraries |
| R-3 | Self-hosted Keycloak must be operated (HA, upgrades, backups) | One Keycloak per instance, clustered (jdbc-ping); database on RDS with its backups; realm-change and upgrade procedures in infra/aws/RUNBOOK.md |
| R-4 | Scale targets unproven until load-tested | H-3 done on a development VM with 1 M users (SLOs met up to 210 req/s on 4 vCPUs shared with the database and generator); production capacity still to be measured on the production environment before launch (H-5) |
| R-5 | The Razorpay adapter has been tested only against a stub of Razorpay's documented API (razorpay.com is not reachable from the build sandbox) | Before going live: run the billing smoke test in DEPLOYMENT.md with Razorpay test-mode keys and a real webhook; fix any field mismatch in `RazorpayGateway` |
