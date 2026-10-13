# Database Design

Status: Phase 1 design (no tables created yet — first migrations arrive in Phase 3). MySQL 9.7 LTS, InnoDB, `utf8mb4` / `utf8mb4_0900_ai_ci`, one schema
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
| `user` | id, `email` (normalized lower-case) **UK**, `status` (INVITED, ACTIVE, DISABLED), `last_login_at`, version |
| `user_identity` | id, user_id FK, `provider` (e.g. `keycloak`), `subject` — **UK(provider, subject)** |
| `user_profile` | user_id PK/FK, display_name, phone, locale, timezone |
| `company_membership` | id, company_id FK, user_id FK, `status` (INVITED, ACTIVE, SUSPENDED, DEACTIVATED), joined_at, version — **UK(company_id, user_id)**, IX(user_id, status) |
| `invitation` | id, company_id FK, email, membership_id FK, `token_hash` (SHA-256, never raw token) UK, `status` (PENDING, ACCEPTED, REVOKED, EXPIRED), expires_at, invited_by — partial uniqueness of one PENDING per (company, email) enforced via generated column `pending_key` = IF(status='PENDING', CONCAT(company_id,email), NULL) **UK** |
| `permission` | id, `code` UK (e.g. `company.users.manage`), `scope` (PLATFORM, COMPANY), description — seeded by migrations, not user-editable |
| `role` | id, `code`, `scope`, `company_id` NULL (system) / FK (custom), `system` bool — **UK(company_id_key, code)** using generated column to treat NULL as global |
| `role_permission` | role_id FK, permission_id FK — PK(role_id, permission_id) |
| `membership_role` | membership_id FK, role_id FK — PK; app rule: role must be COMPANY scope and system or same company |
| `platform_role_assignment` | user_id FK, role_id FK (PLATFORM scope) — PK |

### Company

| Table | Key columns / constraints |
|---|---|
| `company` | id, `code` (slug) UK, legal_name, display_name, `status` (PENDING, ACTIVE, SUSPENDED, DEACTIVATED), country, tax_id, version; IX(status, created_at), IX(display_name) |
| `company_settings` | company_id PK/FK, timezone, locale, branding_logo_url, `allowed_email_domains`, version |
| `company_contact` | id, company_id FK, type (PRIMARY, BILLING, TECHNICAL), name, email, phone |
| `company_status_history` | id, company_id, from_status, to_status, reason, changed_by, changed_at |

### Catalog

| Table | Key columns / constraints |
|---|---|
| `product` | id, `code` UK (immutable), name, description, `status` (DRAFT, ACTIVE, RETIRED), `app_url`, `api_base_url`, `api_audience`, `provisioning_mode` (NONE, SYNC, ASYNC), current_version_id, version |
| `product_version` | id, product_id FK, `version_no` INT — UK(product_id, version_no), `config_json` JSON (validated against a JSON schema), `config_schema_version`, published_at, published_by. Immutable once published. |
| `product_feature` | id, product_id FK, `code` — UK(product_id, code), name, `type` (BOOLEAN, LIMIT), `unit` (SEATS, GB, API_CALLS, RECORDS, …), `default_reset_period` (NONE, DAILY, MONTHLY, BILLING_PERIOD) |

Product service credentials are **not** stored here; they live in Keycloak / secret manager.

### Plans & subscriptions

| Table | Key columns / constraints |
|---|---|
| `subscription_plan` | id, `code` UK, name, `status` (DRAFT, ACTIVE, ARCHIVED), `billing_period` (MONTHLY, QUARTERLY, ANNUAL, CUSTOM), `period_months`, `trial_days`, `public` bool, version |
| `plan_price` | id, plan_id FK, currency, amount_minor, effective_from, effective_to — UK(plan_id, currency, effective_from) |
| `plan_product` | plan_id FK, product_id FK — PK |
| `plan_feature` | id, plan_id FK, product_id FK, feature_id FK, `enabled`, `limit_value` BIGINT NULL (NULL=unlimited), `reset_period` — UK(plan_id, feature_id) |
| `company_subscription` | id, company_id FK, plan_id FK, `status` (TRIAL, ACTIVE, PAST_DUE, SUSPENDED, CANCELLED, EXPIRED), `price_snapshot_minor`, currency, `current_period_start`, `current_period_end`, `trial_end`, `renewal_at`, `cancel_at_period_end` bool, cancelled_at, `pending_plan_id` (scheduled downgrade), version — IX(company_id, status), IX(status, current_period_end) for sweeps; at most one non-terminal subscription per (company, plan) enforced by generated-column UK |
| `subscription_history` | id, subscription_id FK, event (CREATED, ACTIVATED, RENEWED, UPGRADED, DOWNGRADE_SCHEDULED, PAST_DUE, SUSPENDED, CANCELLED, EXPIRED, REACTIVATED), from_status, to_status, from_plan_id, to_plan_id, actor, reason, occurred_at |
| `subscription_invoice` | id, subscription_id FK, company_id, number UK, period_start, period_end, amount_minor, tax_minor, currency, `status` (DRAFT, OPEN, PAID, VOID, UNCOLLECTIBLE), gateway_invoice_id UK, issued_at, due_at |
| `payment_transaction` | id, invoice_id FK, company_id, gateway, `gateway_payment_id` UK(gateway, gateway_payment_id), amount_minor, currency, `status` (CREATED, AUTHORIZED, CAPTURED, FAILED, REFUNDED), failure_code, raw_event_id |
| `payment_webhook_event` | id, gateway, `gateway_event_id` **UK(gateway, gateway_event_id)** (idempotency), signature_verified, payload_hash, received_at, processed_at, status |

### Entitlements & usage

| Table | Key columns / constraints |
|---|---|
| `company_product_entitlement` | id, company_id FK, product_id FK, `status` (ACTIVE, GRACE, SUSPENDED, REVOKED), source_subscription_ids (derived; recomputable), valid_from, valid_until, computed_at, version — **UK(company_id, product_id)** |
| `entitlement_feature` | id, entitlement_id FK, feature_id FK, enabled, limit_value NULL, reset_period — UK(entitlement_id, feature_id) |
| `usage_counter` | id, company_id, feature_id, `period_start`, `period_end`, `used` BIGINT, version — **UK(company_id, feature_id, period_start)** |
| `usage_record` | id, company_id, product_id, feature_id, quantity, `idempotency_key` UK(company_id, idempotency_key), occurred_at, recorded_at — append-only, partition candidate |

### Platform operations

| Table | Key columns / constraints |
|---|---|
| `provisioning_job` | id, company_id, product_id, `action` (PROVISION, UPDATE, DEPROVISION), `status` (PENDING, IN_PROGRESS, AWAITING_CALLBACK, SUCCEEDED, FAILED_RETRYABLE, FAILED_PERMANENT, CANCELLED), `idempotency_key` UK, attempts, max_attempts, next_attempt_at, last_error_code, last_error_message (truncated, sanitized), locked_by, locked_until, version — IX(status, next_attempt_at) |
| `outbox_event` | id, aggregate_type, aggregate_id, event_type, payload JSON, occurred_at, published_at NULL, attempts — IX(published_at, occurred_at) |
| `audit_log` | id, occurred_at, actor_user_id, actor_type (USER, SERVICE, SYSTEM), company_id NULL, action (e.g. `company.suspended`), target_type, target_id, outcome, correlation_id, ip_hash, details JSON (no secrets/tokens) — IX(company_id, occurred_at), IX(actor_user_id, occurred_at), IX(target_type, target_id); append-only (app DB user has no UPDATE/DELETE grant on this table) |
| `notification` | id, company_id NULL, recipient_user_id NULL, recipient_email, template_code, channel, status (QUEUED, SENT, FAILED), attempts, sent_at, error |
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

## 8. Retention

| Data | Retention (default, configurable) |
|---|---|
| Audit log | 7 years (partitioned monthly, archived to object storage after 1 year) |
| Usage records | 24 months raw; counters kept with subscription |
| Webhook events | 18 months |
| Outbox events | 30 days after publish |
| Invitations | 90 days after terminal state |
| Deactivated memberships / users | Personal data anonymized on erasure request; references retained with anonymized profile |
