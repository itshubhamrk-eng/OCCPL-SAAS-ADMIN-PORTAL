# Database Design

Status: Phase 7 — provisioning tables (`provisioning_job`, `company_product_provisioning`), `product.service_client_id`, `audit_log.actor_client_id`; Phase 6 plans, subscriptions, entitlements and usage tables, plus `product`, `product_version`, `product_feature` (catalog) plus Phase 3–4 tables `platform_user`, `user_identity`, `user_profile`, `company`, `company_settings`,
`company_contact`, `company_status_history`, `company_membership`, `invitation`, `permission`, `role`,
`role_permission`, `platform_role_assignment`, `membership_role`, `audit_log`, `notification` are created by Flyway (`db/migration/<module>/`); the rest of this design follows in later phases. MySQL 9.7 LTS, InnoDB, `utf8mb4` / `utf8mb4_0900_ai_ci`, one schema
`occpl_core`. Product services own separate schemas/databases.

## 1. Conventions

| Topic | Rule |
|---|---|
| Primary keys | `id BINARY(16)` UUID v7, generated in the application (ADR-0003) |
| Timestamps | `created_at`, `updated_at` `DATETIME(6)` UTC, set by the application |
| Actor columns | `created_by`, `updated_by` `BINARY(16)` user id (nullable for system) |
| Optimistic locking | `version BIGINT NOT NULL DEFAULT 0` on mutable aggregates |
| Enums | `VARCHAR(32)` + `CHECK` constraint (not MySQL `ENUM`, to ease migrations) |
| Money | `amount_minor BIGINT` (paise/cents) + `currency CHAR(3)` |
| Tenant column | Every tenant-owned table has `company_id BINARY(16) NOT NULL` leading its main indexes |
| Naming | snake_case tables (singular), `fk_<table>_<ref>`, `uk_<table>_<cols>`, `ix_<table>_<cols>` |
| Deletion | Hard delete by default. Status-based lifecycle (e.g. `DEACTIVATED`) for business entities that must remain referenceable. `deleted_at` soft delete only where noted. |
| JSON | `JSON` columns only for validated, versioned configuration (product config), never for queried relational data |

## 2. Entity relationship overview

```
user 1─* user_identity                 (IdP subject mapping; one per IdP)
user 1─1 user_profile
user 1─* company_membership *─1 company
company 1─1 company_settings, company 1─* company_contact
company_membership *─* role  via membership_role
role *─* permission           via role_permission
role.company_id NULL ⇒ system/platform role template; NOT NULL ⇒ company-custom role
platform_role_assignment: user *─* role (scope=PLATFORM)   (Super Admin etc.)
invitation *─1 company (pending membership before user accepts)

product 1─* product_version (versioned config) 1─* product_feature
subscription_plan *─1 product? (NULL for bundles) ; subscription_plan 1─* plan_price
subscription_plan 1─* plan_product 1─* plan_feature (feature enabled + limit)
company 1─* company_subscription *─1 subscription_plan
company_subscription 1─* subscription_history
company_subscription 1─* subscription_invoice 1─* payment_transaction

company 1─* company_product_entitlement *─1 product     (derived from active subscriptions)
company_product_entitlement 1─* entitlement_feature (enabled, limit_value, reset_period)
entitlement_feature 1─* usage_counter (per period)
usage_record (append-only usage events, tenant scoped)

provisioning_job *─1 company, *─1 product
outbox_event, audit_log, notification, idempotency_key, shedlock
```

## 3. Tables (initial)

### Identity & access

| Table | Key columns / constraints |
|---|---|
| `platform_user` | id, `email` (normalized lower-case) **UK**, `status` (INVITED, ACTIVE, DISABLED), `last_login_at` (refreshed at most every 15 min), version. Named `platform_user` because USER is a MySQL keyword. |
| `user_identity` | id, user_id FK, `provider` (e.g. `keycloak`), `subject` — **UK(provider, subject)**, **UK(user_id, provider)** (one subject per provider: prevents account takeover by a second IdP account with the same email) |
| `user_profile` | user_id PK/FK, display_name — IX(display_name) for member search by name prefix (Phase 9), phone, locale, timezone |
| `company_membership` | id, company_id FK, user_id FK, `status` (INVITED, ACTIVE, SUSPENDED, DEACTIVATED), joined_at, version — **UK(company_id, user_id)**, IX(user_id, status) |
| `invitation` | id, company_id FK, membership_id FK, email, `token_hash` CHAR(64) (SHA-256, never the raw token) **UK**, `status` (PENDING, ACCEPTED, REVOKED, EXPIRED), expires_at, invited_by, accepted_at, version — one PENDING per membership via generated column `pending_key` = IF(status='PENDING', membership_id, NULL) **UK**; IX(company_id, status, created_at), IX(status, expires_at) |
| `permission` | `code` PK (e.g. `company.users.manage`), `scope` (PLATFORM, COMPANY), description — seeded by the repeatable migration, not user-editable |
| `role` | id, `code`, `scope`, `company_id` NULL (system) / FK (custom), `system` bool — **UK(company_id_key, code)** using generated column to treat NULL as global |
| `role_permission` | role_id FK, permission_code FK — PK(role_id, permission_code) |
| `membership_role` | membership_id FK, role_id FK — PK; app rule: role must be COMPANY scope and system or same company |
| `platform_role_assignment` | user_id FK, role_id FK (PLATFORM scope) — PK |

### Company

| Table | Key columns / constraints |
|---|---|
| `company` | id, `code` (slug) UK, legal_name, display_name, `status` (PENDING, ACTIVE, SUSPENDED, DEACTIVATED), country, tax_id, version; IX(status, created_at), IX(display_name) |
| `company_settings` | company_id PK/FK, time_zone, locale, `allowed_email_domains` (comma-separated, empty = any), updated_by, version (branding later) |
| `company_contact` | id, company_id FK, type (PRIMARY, BILLING, TECHNICAL) — **UK(company_id, type)**, name, email, phone, version |
| `company_status_history` | id, company_id, from_status, to_status, reason, changed_by, changed_at |

### Catalog

| Table | Key columns / constraints |
|---|---|
| `product` | id, `code` **UK** (immutable), name, description, `status` (DRAFT, ACTIVE, RETIRED), `app_url`, `api_base_url`, `api_audience` **UK**, `service_client_id` **UK** (NULL allowed; Keycloak client the product uses to call Core), `current_version_id` FK → product_version, created/updated by/at, version; IX(status, name), IX(name). Provisioning mode lives in the versioned configuration, not on the row. |
| `product_version` | id, product_id FK, `version_no` — **UK(product_id, version_no)**, `schema_version`, `config_json` JSON (canonical: keys sorted), `config_hash` CHAR(64) (SHA-256 of the canonical JSON, used to reject identical versions), `status` (DRAFT, PUBLISHED), notes, created_by/at, published_by/at. Content columns are never updated. |
| `product_feature` | id, product_id FK, `code` — **UK(product_id, code)**, name, description, `type` (BOOLEAN, LIMIT), `unit` (SEATS, STORAGE_GB, RECORDS, API_CALLS, TRANSACTIONS, DOCUMENTS, MESSAGES, COUNT), `reset_period` (NONE, DAILY, MONTHLY, BILLING_PERIOD), `status` (ACTIVE, DEPRECATED), version; CHECK: BOOLEAN ⇒ no unit/period, LIMIT ⇒ both. |

Product service credentials are **not** stored here; they live in Keycloak / secret manager.

### Plans & subscriptions (implemented)

| Table | Key columns / constraints |
|---|---|
| `subscription_plan` | id, `code` **UK**, name, description, `status` (DRAFT, ACTIVE, ARCHIVED), `billing_period` (MONTHLY, QUARTERLY, HALF_YEARLY, ANNUAL), `trial_days` 0–365, published_at, version |
| `plan_price` | **PK(plan_id, currency)**, amount_minor ≥ 0 |
| `plan_product` | **PK(plan_id, product_id)** |
| `plan_feature` | **PK(plan_id, feature_id)**, product_id, `enabled`, `limit_value` (NULL = unlimited) |
| `company_subscription` | id, company_id, plan_id, `status` (TRIAL, ACTIVE, PAST_DUE, SUSPENDED, CANCELLED, EXPIRED), currency, `price_minor` (snapshot), billing_period, current_period_start/end, trial_end, cancel_at_period_end, pending_plan_id (scheduled downgrade), status_changed_at, cancelled_at, ended_at, `data_retention_until`, version; generated `live_key` = company+plan while live → **UK** (one live subscription per company and plan); IX(company_id, status), IX(status, current_period_end), IX(status, trial_end), IX(status, status_changed_at) for sweeps |
| `subscription_history` | id, subscription_id, event, from/to status, from/to plan, actor_user_id (NULL = system), reason, occurred_at |

Invoices and payment transactions arrive with billing (Razorpay).

### Entitlements & usage (implemented)

| Table | Key columns / constraints |
|---|---|
| `company_product_entitlement` | id, company_id, product_id — **UK(company_id, product_id)**, `status` (ACTIVE, GRACE, SUSPENDED), valid_until, computed_at. Derived; fully recomputed per company on every subscription change. |
| `entitlement_feature` | **PK(entitlement_id, feature_id)**, enabled, `limit_value` (NULL = unlimited), `period_anchor` (BILLING_PERIOD window start) |
| `usage_counter` | **PK(company_id, feature_id, period_start)**, `used` ≥ 0; stock features use period_start 1970-01-01 |
| `usage_record` | id, company_id, feature_id, quantity (negative = release), `idempotency_key` — **UK(company_id, idempotency_key)**, period_start, used_after, recorded_at |

### Billing (implemented)

| Table | Key columns / constraints |
|---|---|
| `invoice` | id (also sent to Razorpay as `receipt`), company_id, subscription_id, gateway (RAZORPAY), gateway_invoice_id — **UK(gateway, gateway_invoice_id)**, gateway_number, `status` (CREATING, ISSUE_FAILED, ISSUED, PARTIALLY_PAID, PAID, CANCELLED, EXPIRED), description, currency, amount_minor > 0, amount_paid_minor, period_start/period_end (the subscription period the invoice pays for), payment_url, customer_email, issued/expires/paid_at, `payment_applied` (APPLIED, ALREADY_COVERED, NOT_APPLICABLE), last_error, version. Generated `open_key` = subscription + period_start while CREATING/ISSUED/PARTIALLY_PAID/PAID → **UK(open_key)**: at most one open or paid invoice per period, enforced by the database across instances. IX(company_id, created_at), IX(subscription_id, period_start), IX(status, created_at) |
| `payment_transaction` | id, invoice_id, company_id, gateway_payment_id, `status` (CAPTURED, FAILED) — **UK(gateway_payment_id, status)**, amount_minor, currency, method, error_reason, occurred_at |
| `payment_webhook_event` | **PK(gateway, event_id)** (idempotency), event_type, body_sha256 (the payload itself is not stored), outcome, received_at |

The invoice row is committed as CREATING before Razorpay is called (no HTTP inside a transaction);
a row still CREATING after 10 minutes (process died mid-call) becomes ISSUE_FAILED and must be
checked in the Razorpay dashboard (BACKLOG B-6).

### Platform operations

| Table | Key columns / constraints |
|---|---|
| `provisioning_job` (implemented) | id (also the `Idempotency-Key` sent to the product), company_id, product_id, `operation` (PROVISION, DEPROVISION), `status` (PENDING, RUNNING, AWAITING_CALLBACK, SUCCEEDED, FAILED, CANCELLED), attempts, max_attempts, next_attempt_at, `locked_until` (lease while RUNNING), callback_deadline, config_version_no, last_http_status, last_error (≤ 1000 chars, no secrets), created/updated/completed_at, version — IX(status, next_attempt_at), IX(status, locked_until), IX(status, callback_deadline), IX(company_id, product_id, created_at), IX(created_at) |
| `company_product_provisioning` (implemented) | **PK(company_id, product_id)**, `state` (PROVISIONING, PROVISIONED, FAILED, DEPROVISION_SCHEDULED, DEPROVISIONING, DEPROVISIONED), last_job_id, provisioned_at, deprovision_at, updated_at, version |
| `audit_log` | id, occurred_at, actor_user_id, `actor_client_id` (SERVICE actors, Phase 7), actor_type (USER, SERVICE, SYSTEM), company_id NULL, action (e.g. `company.suspended`), target_type, target_id, outcome, correlation_id, ip_hash, details JSON (no secrets/tokens) — IX(company_id, occurred_at), IX(actor_user_id, occurred_at), IX(target_type, target_id); append-only (app DB user has no UPDATE/DELETE grant on this table) |
| `notification` | id, company_id NULL, template, recipient_email, subject, body_text/body_html (cleared after sending or permanent failure), status (QUEUED, SENT, FAILED), attempts, next_attempt_at, last_error, sent_at — IX(status, next_attempt_at); claimed with `FOR UPDATE SKIP LOCKED` |
| `idempotency_key` | key, scope, request_hash, response_status, response_body, created_at, expires_at — for idempotent POST APIs |
| `shedlock` | standard ShedLock table for single-runner scheduled jobs |

## 4. Usage limit semantics

- **Limit types**: *stock* limits (seats, records, storage GB) measured as current quantity;
  *flow* limits (API calls, documents/month) counted within a reset period.
- **Reset periods**: `NONE` (stock), `DAILY` (00:00 company timezone), `MONTHLY` (calendar
  month, company timezone), `BILLING_PERIOD` (aligned to subscription period).
- **Effective limit** for a company/feature = maximum across all active subscriptions granting
  it (A-3), `NULL` = unlimited.
- **Concurrency**: consumption is a single conditional update
  `UPDATE usage_counter SET used = used + :q WHERE company_id=? AND feature_id=? AND period_start=? AND (:limit IS NULL OR used + :q <= :limit)`;
  affected rows = 0 ⇒ `LIMIT_EXCEEDED`. Row created on first use with `INSERT … ON DUPLICATE KEY`.
  Idempotency via `usage_record.idempotency_key`. No read-modify-write in the application.
- **Seats** are checked inside the membership transaction by counting ACTIVE+INVITED
  memberships with `SELECT … FOR UPDATE` on the company's entitlement row to serialize
  concurrent invitations.
- **Downgrades below current usage**: never delete data; stock limits block new consumption
  until usage is within the new limit; the Company Admin is notified.

## 5. Subscription state machine

| From → To | Trigger |
|---|---|
| (new) → TRIAL | Assigned with plan trial_days > 0 |
| (new) → ACTIVE | Assigned without trial and (payment captured OR Super Admin marks as manually invoiced) |
| TRIAL → ACTIVE | Payment captured / manual activation before trial_end |
| TRIAL → EXPIRED | trial_end reached without activation |
| ACTIVE → ACTIVE | Renewal payment captured (period advanced, history RENEWED) |
| ACTIVE → PAST_DUE | Renewal payment failed or invoice overdue |
| PAST_DUE → ACTIVE | Payment captured within grace |
| PAST_DUE → SUSPENDED | Grace period elapsed (D-5) |
| SUSPENDED → ACTIVE | Payment captured / Super Admin reactivation |
| ACTIVE/PAST_DUE/SUSPENDED/TRIAL → CANCELLED | Immediate cancellation by Super Admin |
| ACTIVE → (cancel_at_period_end) → EXPIRED | Scheduled cancellation reaches period end |
| SUSPENDED → EXPIRED | Retention window elapses |
| CANCELLED, EXPIRED | Terminal |

**Product access** derived from subscription status: TRIAL, ACTIVE → entitlement ACTIVE;
PAST_DUE → GRACE (access allowed, banner shown); SUSPENDED → SUSPENDED (access denied, data
kept); CANCELLED/EXPIRED → REVOKED (deprovision job scheduled after retention). Company status
SUSPENDED/DEACTIVATED overrides everything (no access).

Plan changes: upgrade = immediate, new limits immediately, proration invoice; downgrade =
`pending_plan_id` applied at `current_period_end`. Entitlements recomputed on every
subscription event (event-driven, idempotent full recompute for the company).

## 6. Migration strategy

- Flyway, versioned scripts `V<yyyymmddHHmm>__<module>_<description>.sql` in
  `db/migration/<module>/`; repeatable `R__seed_permissions.sql` for permission/role seed data.
- Hibernate `ddl-auto=validate` in every environment; schema only changes via Flyway.
- Migrations must be backward compatible with the previous app version (expand → migrate →
  contract) to allow rolling deploys and rollback of the application without DB rollback.
- Large-table changes use online DDL (`ALGORITHM=INSTANT/INPLACE`) or a pt-online-schema-change
  / gh-ost runbook; never long table locks during business hours.
- CI runs all migrations against a fresh MySQL 9.7 Testcontainer (implemented: `CorePlatformApplicationIT`) and against a copy of the
  previous release's schema; `flyway validate` gates deployment.
- Separate DB users: `occpl_migrator` (DDL, used only by the migration step) and `occpl_app`
  (DML only; no UPDATE/DELETE on `audit_log`).

## 7. Indexing and query rules

- Every list endpoint is paginated (max page size 100) with a deterministic sort including `id`.
- Keyset (seek) pagination for very large lists (audit log, usage records, platform user list).
- Tenant-owned queries always include `company_id` as the leading predicate.
- Avoid N+1: fetch joins / batched DTO projections within a module; no lazy loading in views
  (`spring.jpa.open-in-view=false`).
- Search on company/user names: prefix `LIKE 'x%'` on indexed columns initially; full-text or
  an external search engine only if measured necessary.

- Member search (`MembershipQueries.search`) with a term of three or more characters starts from the
  users matching the prefix (`uk_platform_user_email`, `ix_user_profile_display_name`) and joins the
  company's memberships by `(company_id, user_id)`; shorter terms filter the company's members. Load
  test H-3: 214 ms → 4 ms for a 50,000-member company (load-tests/RESULTS.md).
- Backups: MySQL Shell dumps + binary-log increments, restore drill in `infra/backup/DRILL.md`.

## 8. Retention

| Data | Retention (default, configurable) |
|---|---|
| Audit log | 7 years (partitioned monthly, archived to object storage after 1 year) |
| Usage records | 24 months raw; counters kept with subscription |
| Webhook events | 18 months |
| Outbox events | 30 days after publish |
| Invitations | 90 days after terminal state |
| Deactivated memberships / users | Personal data anonymized on erasure request; references retained with anonymized profile |
