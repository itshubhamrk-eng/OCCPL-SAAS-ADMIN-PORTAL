# OCCPL Innovations — Multi-Product SaaS Platform

Status: **Phase 1 (Discovery & Architecture) — awaiting approval.** No application code exists yet.

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

### Technology assessment and version notes

| Area | Mandated | Proposed pin (confirm at Phase 2 start) | Note |
|---|---|---|---|
| Java | 21 | 21 LTS | Available locally |
| Spring Boot | 3.x | **3.5.x latest patch** | Spring's published OSS support window for 3.5 was scheduled to end mid-2026. If the business requires vendor-OSS-supported versions, choose Boot 4.x instead (Decision D-1). The architecture does not depend on the choice. |
| Angular | supported stable | 22.x (current `latest`), strict TS | 21 LTS is acceptable if a longer support window is preferred |
| DB | MySQL 8 | MySQL 8.4 LTS | |
| IdP | OIDC, e.g. Keycloak | Keycloak 26.x (self-hosted) | Managed alternative possible (D-2) |
| Migrations | Flyway | Flyway (Boot-managed version) + `flyway-mysql` | |
| Cache / rate limit | Redis when justified | Redis 7 (entitlement cache, rate limiting) | Justified in ADR-0006 |
| Module boundaries | — | Spring Modulith verification + ArchUnit | ADR-0001 |
| API docs | OpenAPI | springdoc-openapi | |
| Tests | JUnit 5, Mockito, Spring Boot Test | + Testcontainers (MySQL, Keycloak), Angular TestBed, k6 for load | |

## 4. Explicit assumptions

| # | Assumption |
|---|---|
| A-1 | A single OCCPL-run IdP realm holds all human users; companies do not bring their own IdP initially (enterprise SSO/federation is a later backlog item). |
| A-2 | Users are identified globally by email at invitation time; the stable key is the platform `user.id` (UUID) mapped to the IdP subject. |
| A-3 | One subscription per (company, plan). A company may hold several concurrent subscriptions (e.g. one per product, or a bundle plan covering several products). |
| A-4 | Currency is per plan price (ISO-4217). Initial currency INR; multi-currency supported in schema. Tax/GST calculation is delegated to the payment gateway/invoicing provider (D-4). |
| A-5 | Upgrades take effect immediately with proration computed by the billing module; downgrades take effect at the next renewal (see DATABASE.md §6). |
| A-6 | Seat limits count ACTIVE + INVITED memberships with access to the product. |
| A-7 | Product services are trusted OCCPL-built services reachable over a private network or mTLS-protected public endpoint. |
| A-8 | English UI only at launch; i18n-ready structure in Angular. |
| A-9 | Data residency: single region (India) at launch. |
| A-10 | Email is the only notification channel at launch (SMTP / transactional email provider). |

## 5. Unresolved business decisions

| # | Decision needed | Blocks | Default if not answered |
|---|---|---|---|
| D-1 | Spring Boot 3.5.x (mandated) vs 4.x (newer support window) | Phase 2 | 3.5.x as mandated |
| D-2 | Self-hosted Keycloak vs managed IdP (Auth0, Cognito, Entra) | Phase 3 | Self-hosted Keycloak |
| D-3 | Payment gateway (Razorpay, Stripe, other) | Billing backlog items | Razorpay (India), behind a gateway port |
| D-4 | Invoicing/tax (GST) ownership: gateway vs accounting system vs in-house | Billing | Gateway-generated invoices; we store references |
| D-5 | Grace period length for PAST_DUE before SUSPENDED; retention after CANCELLED/EXPIRED | Phase 6 | 7 days grace; data retained 90 days after expiry |
| D-6 | Confirm SLOs in §2 | Phase 9 | Provisional values above |
| D-7 | Can a Company Admin self-serve purchases/upgrades, or only Super Admin assigns plans? | Phase 6/8 | Super Admin assigns; Company Admin views (self-serve later) |
| D-8 | Should users self-register, or only via invitation? | Phase 3 | Invitation-only |
| D-9 | Product launch model: separate domains per product with SSO, or path-based behind one gateway | Phase 7 | Separate subdomains, shared IdP SSO |
| D-10 | Personal-data retention/export obligations (DPDP Act 2023 or customer contracts) | Phase 9 | Implement export + deletion workflow, retention configurable |
