# API Specification (initial inventory)

Status: Phase 1 inventory; the error format (§2) and conventions are implemented in Phase 2. The authoritative contract will be the generated OpenAPI document
(`/v3/api-docs`, Swagger UI in non-production) once endpoints are implemented. Nothing below is
implemented yet.

## 1. Conventions

- Base path `/api/v1`. JSON only (`application/json`), UTF-8, camelCase fields, ISO-8601 UTC
  timestamps, UUID strings for ids, money as `{ "amountMinor": 49900, "currency": "INR" }`.
- Company-scoped resources live under `/api/v1/companies/{companyId}/…`. Platform-wide admin
  listings live under `/api/v1/platform/…` or top-level resources requiring PLATFORM
  permissions.
- Pagination: `?page=0&size=20&sort=createdAt,desc` (size max 100). Response:
  `{ "items": [...], "page": 0, "size": 20, "totalItems": 123, "totalPages": 7 }`.
  Keyset endpoints return `{ "items": [...], "nextCursor": "..." }`.
- Filtering: explicit query params (`status`, `q` prefix search, `createdFrom/To`); sort fields
  from an allowlist.
- Concurrency: responses carry `ETag` (entity version); updates require `If-Match` → 412 on
  mismatch.
- Idempotency: `POST` creating resources and all payment/provisioning/usage endpoints accept
  `Idempotency-Key`; replay returns the original response.
- Correlation: `X-Correlation-Id` accepted/generated and echoed.
- Status codes: 200, 201 (+`Location`), 202, 204, 400 validation, 401, 403, 404 (also for
  cross-tenant), 409 conflict/business rule, 412, 422 state-transition violation, 429, 500.

## 2. Error format (RFC 9457)

```json
{
  "type": "https://api.occpl.com/problems/validation-error",
  "title": "Validation failed",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "detail": "One or more fields are invalid.",
  "instance": "/api/v1/companies",
  "correlationId": "4f0c…",
  "errors": [ { "field": "legalName", "code": "NotBlank", "message": "must not be blank" } ]
}
```

Stable `code` values: `VALIDATION_ERROR`, `NOT_FOUND`, `FORBIDDEN`, `CONFLICT`,
`DUPLICATE_MEMBERSHIP`, `INVITATION_PENDING`, `LAST_ADMIN`, `INVALID_STATE_TRANSITION`,
`LIMIT_EXCEEDED`, `ENTITLEMENT_REQUIRED`, `PRECONDITION_FAILED`, `RATE_LIMITED`, `UNAUTHENTICATED`,
`ACCOUNT_DISABLED`, `EMAIL_NOT_VERIFIED`, `IDENTITY_CONFLICT`, `COMPANY_SUSPENDED`, `METHOD_NOT_ALLOWED`,
`UNSUPPORTED_MEDIA_TYPE`,
`IDEMPOTENCY_KEY_REUSED`, `INTERNAL_ERROR`.

## 3. Endpoint inventory

Legend — Auth: **P:** platform permission, **C:** company permission (via membership in
`{companyId}`), **S:** service client scope, **Me:** any authenticated active user.

### Identity / current user

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/me` | Me | Profile, platform roles/permissions/`mfaRequired`, active memberships (company, roles, permissions) — implemented |
| PUT | `/me/profile` | Me | Replace own profile (displayName, phone E.164, locale, IANA time zone) — implemented |
| POST | `/invitations/{token}/accept` | Me (token-bound) | Accept invitation; links identity to membership |

### Companies

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/companies` | P:`platform.companies.manage` | Create company + initial Company Admin invitation (+ optional subscriptions) |
| GET | `/companies` | P:`platform.companies.read` | Search/filter/paginate |
| GET | `/companies/{companyId}` | P or C:`company.profile.read` | Details |
| PATCH | `/companies/{companyId}` | P or C:`company.profile.manage` (limited fields) | Update |
| POST | `/companies/{companyId}/activate` · `/suspend` · `/deactivate` | P:`platform.companies.manage` | Lifecycle (reason required) |
| GET/PUT | `/companies/{companyId}/settings` | C:`company.profile.read/manage` | Settings |
| GET/POST/PUT/DELETE | `/companies/{companyId}/contacts[/{id}]` | C:`company.profile.manage` | Contacts |

### Users & memberships

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/users` | P:`platform.users.read` | Platform user search (keyset) |
| GET | `/users/{userId}/memberships` | P:`platform.users.read` | User's memberships across companies |
| POST | `/users/{userId}/disable` · `/enable` | P:`platform.users.manage` | Platform-wide disable (also disables at IdP) |
| GET | `/companies/{companyId}/memberships` | C:`company.users.read` | List members (filter by status/role/q) |
| POST | `/companies/{companyId}/invitations` | C:`company.users.manage` | Invite user (seat check, duplicate check) |
| GET | `/companies/{companyId}/invitations` | C:`company.users.read` | List invitations |
| POST | `/companies/{companyId}/invitations/{id}/resend` · `/revoke` | C:`company.users.manage` | Resend (rotates token) / revoke |
| GET | `/companies/{companyId}/memberships/{id}` | C:`company.users.read` | Member detail |
| POST | `/companies/{companyId}/memberships/{id}/suspend` · `/reactivate` · `/deactivate` | C:`company.users.manage` | Membership lifecycle (last-admin rule) |
| PUT | `/companies/{companyId}/memberships/{id}/roles` | C:`company.roles.assign` | Replace role set (no escalation) |

### Roles & permissions

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/permissions?scope=COMPANY` | Me | Permission catalogue |
| GET | `/companies/{companyId}/roles` | C:`company.users.read` | System + custom roles |
| POST/PUT/DELETE | `/companies/{companyId}/roles[/{roleId}]` | C:`company.roles.manage` | Custom roles (COMPANY permissions only) |
| GET/PUT | `/platform/users/{userId}/roles` | P:`platform.roles.manage` | Platform role assignment; last SUPER_ADMIN cannot be removed (409 `LAST_ADMIN`) — implemented |
| GET | `/platform/roles` | P:`platform.roles.manage` | Platform roles with permissions — implemented |

### Product catalogue

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/products` | P:`platform.products.manage` | Register product (DRAFT) |
| GET | `/products` | P:`platform.products.read` | List/filter |
| GET/PATCH | `/products/{productId}` | P | Details / update metadata |
| POST | `/products/{productId}/activate` · `/retire` | P:`platform.products.manage` | Lifecycle |
| GET/POST | `/products/{productId}/versions` | P:`platform.products.manage` | List / publish validated config version |
| GET/POST/PATCH | `/products/{productId}/features[/{featureId}]` | P:`platform.products.manage` | Features |

### Plans

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST/GET | `/plans` | P:`platform.plans.manage`/read | Create / list |
| GET/PATCH | `/plans/{planId}` | P | Details / update DRAFT plan |
| POST | `/plans/{planId}/publish` · `/archive` | P:`platform.plans.manage` | Lifecycle (ACTIVE plans immutable except archive; changes = new plan) |
| PUT | `/plans/{planId}/prices` · `/products` · `/features` | P:`platform.plans.manage` | Configure prices, products, feature limits |

### Subscriptions

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/companies/{companyId}/subscriptions` | P:`platform.subscriptions.manage` | Assign plan (TRIAL or pending payment/ACTIVE) |
| GET | `/companies/{companyId}/subscriptions` | P or C:`company.subscription.read` | List |
| GET | `/companies/{companyId}/subscriptions/{id}` | P or C:`company.subscription.read` | Detail + history |
| POST | `…/subscriptions/{id}/change-plan` | P:`platform.subscriptions.manage` | Upgrade (immediate) / downgrade (scheduled) |
| POST | `…/subscriptions/{id}/cancel` | P:`platform.subscriptions.manage` | `{ "mode": "IMMEDIATE" \| "PERIOD_END", "reason": … }` |
| POST | `…/subscriptions/{id}/renew` · `/reactivate` | P:`platform.subscriptions.manage` | Manual renewal / reactivation |
| GET | `/platform/subscriptions` | P:`platform.subscriptions.read` | Cross-company listing (status, renewal window) |

### Entitlements & usage

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/companies/{companyId}/entitlements` | P or C:`company.usage.read` | Products, features, limits, current usage |
| GET | `/entitlements/check?companyId&productCode&featureCode&userSub` | S:`entitlements:read` | Product-service check (membership + entitlement + company status) |
| POST | `/usage/consume` | S:`usage:write` | Atomic consume with idempotency key → 200 or 409 `LIMIT_EXCEEDED` |
| POST | `/usage/release` | S:`usage:write` | Release stock usage (e.g. records deleted) |
| POST | `/companies/{companyId}/entitlements/recalculate` | P:`platform.subscriptions.manage` | Force recompute (ops) |

### Provisioning

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/platform/provisioning-jobs` | P:`platform.provisioning.read` | Filter by status/product/company |
| GET | `/platform/provisioning-jobs/{id}` | P | Detail with attempts |
| POST | `/platform/provisioning-jobs/{id}/retry` · `/cancel` | P:`platform.provisioning.manage` | Manual retry / cancel |
| POST | `/provisioning/callbacks` | S:`provisioning:callback` | Async completion from product service (idempotent) |
| GET | `/companies/{companyId}/products` | C:`company.products.launch` | Entitled products with launch URLs and provisioning state |

### Billing

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/companies/{companyId}/invoices` | P or C:`company.billing.read` | Billing history |
| GET | `/companies/{companyId}/invoices/{id}` | P or C:`company.billing.read` | Invoice detail |
| POST | `/companies/{companyId}/invoices/{id}/payment-session` | C:`company.billing.manage` | Create gateway checkout (if D-7 allows) |
| POST | `/webhooks/payments/{gateway}` | Signature (no user auth) | Gateway webhook, idempotent by event id |

### Audit, notifications, reporting

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/audit-logs` | P:`platform.audit.read` | Keyset search (actor, company, action, target, date range) |
| GET | `/companies/{companyId}/audit-logs` | C:`company.audit.read` | Company-scoped audit trail |
| GET | `/platform/reports/overview` | P:`platform.reports.read` | Companies by status, active subscriptions, MRR estimate, provisioning failures |
| GET | `/companies/{companyId}/dashboard` | C:`company.profile.read` | Company dashboard aggregates |
| GET/PUT | `/platform/settings` | P:`platform.settings.manage` | Platform settings (non-secret) |

### Operational (not under `/api`)

`/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus` (internal
network only), `/v3/api-docs`, `/swagger-ui` (disabled in production).

## 4. Example: current user

```http
GET /api/v1/me
Authorization: Bearer <access token>
→ 200
{ "id": "01a1…", "email": "admin@occpl.local", "displayName": "Platform Admin",
  "platform": { "roles": ["SUPER_ADMIN"], "permissions": [], "mfaRequired": true },
  "memberships": [ { "membershipId": "…", "companyId": "…", "companyCode": "acme", "companyName": "Acme",
                     "companyStatus": "ACTIVE", "roles": ["COMPANY_ADMIN"], "permissions": ["company.users.manage", "…"] } ] }
```

`permissions` lists only what is granted for this token: platform permissions are empty and
`mfaRequired` is true when the user holds platform roles but signed in without an authenticator app.
Rate-limited responses carry `X-RateLimit-Remaining`; 429 adds `Retry-After`.

## 5. Example: create company

```http
POST /api/v1/companies
Idempotency-Key: 7b1e…
{
  "code": "acme-industries",
  "legalName": "Acme Industries Pvt Ltd",
  "displayName": "Acme",
  "country": "IN",
  "primaryContact": { "name": "R. Sharma", "email": "admin@acme.example" },
  "initialAdminEmail": "admin@acme.example",
  "subscriptions": [ { "planId": "0191…", "startTrial": true } ]
}
→ 201 Created, Location: /api/v1/companies/0192…
{ "id": "0192…", "code": "acme-industries", "status": "ACTIVE", "version": 0,
  "initialAdminInvitation": { "id": "…", "status": "PENDING" },
  "subscriptions": [ { "id": "…", "status": "TRIAL" } ],
  "provisioning": [ { "productCode": "inventory", "jobStatus": "PENDING" } ] }
```

Company creation, admin membership/invitation, subscriptions, entitlements and outbox events
commit in one transaction; provisioning calls happen asynchronously after commit.
