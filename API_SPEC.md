# API Specification (initial inventory)

Status: endpoints marked "implemented" (or in sections marked implemented) exist as of Phase 7; the
rest is the planned inventory. The authoritative contract for implemented endpoints is the generated
OpenAPI document (`/v3/api-docs`, Swagger UI in non-production).

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
| GET | `/me/profile` | Me | Own profile (displayName, phone, locale, time zone) — implemented (Phase 8, used by the portal's profile page) |
| PUT | `/me/profile` | Me | Replace own profile (displayName, phone E.164, locale, IANA time zone) — implemented |
| POST | `/invitations/{token}/accept` | Me (token-bound) | Accept invitation; links identity to membership |

### Companies (implemented)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/companies` | P:`platform.companies.manage` | Create company, settings, primary contact and the initial Company Admin invitation (one transaction) |
| GET | `/companies` | P:`platform.companies.read` | `q` (code/name prefix), `status`, `country`, `page`, `size`, `sort=createdAt\|displayName\|code\|status,(asc\|desc)` |
| GET | `/companies/{companyId}` | P:`platform.companies.read` or C:`company.profile.read` | Details + `ETag` |
| PATCH | `/companies/{companyId}` | P:`platform.companies.manage` or C:`company.profile.manage` | Omitted fields unchanged; company admins may change `displayName` only; optional `If-Match` |
| POST | `/companies/{companyId}/activate` · `/suspend` · `/deactivate` | P:`platform.companies.manage` | Lifecycle; `{"reason"}` required for suspend/deactivate; 422 on invalid transition |
| GET | `/companies/{companyId}/status-history` | P or C:`company.profile.read` | Lifecycle history, newest first |
| GET/PUT | `/companies/{companyId}/settings` | read: `company.profile.read`; write: `company.profile.manage` (or platform) | `timeZone`, `locale`, `allowedEmailDomains` (restricts invitations); `ETag`/`If-Match` |
| GET | `/companies/{companyId}/contacts` | P or C:`company.profile.read` | Contacts |
| PUT/DELETE | `/companies/{companyId}/contacts/{PRIMARY\|BILLING\|TECHNICAL}` | P or C:`company.profile.manage` | Upsert / remove (PRIMARY cannot be removed) |

### Users & memberships (implemented)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/users` | P:`platform.users.read` | Keyset list: `after` (cursor), `size` ≤ 100, `status`, `q` (email prefix) → `{items, nextCursor}` |
| GET | `/users/{userId}` | P:`platform.users.read` | Detail incl. platform roles |
| GET | `/users/{userId}/memberships` | P:`platform.users.read` | User's memberships across companies |
| POST | `/users/{userId}/disable` · `/enable` | P:`platform.users.manage` | Platform-wide; disable also disables the Keycloak account and ends sessions; not for self or a Super Admin |
| GET | `/companies/{companyId}/memberships` | P:`platform.companies.read` or C:`company.users.read` | `status`, `role`, `q` (email/name prefix), paging, `sort=createdAt\|email\|displayName\|status\|joinedAt` |
| GET | `/companies/{companyId}/memberships/{id}` | same | Member detail |
| GET | `/companies/{companyId}/roles` | same | Assignable roles with permissions |
| POST | `/companies/{companyId}/memberships/{id}/suspend` · `/reactivate` · `/deactivate` | P:`platform.companies.manage` or C:`company.users.manage` | Lifecycle; no self-changes; last admin protected (409 `LAST_ADMIN`); no acting on members with more permissions (403) |
| PUT | `/companies/{companyId}/memberships/{id}/roles` | P:`platform.companies.manage` or C:`company.roles.assign` | Replace roles; only roles within the actor's own permissions |
| POST | `/companies/{companyId}/invitations` | P:`platform.companies.manage` or C:`company.users.manage` | `{email, roles[], firstName?, lastName?}`; 409 `INVITATION_PENDING` / `DUPLICATE_MEMBERSHIP`; 400 if the domain is not allowed |
| GET | `/companies/{companyId}/invitations` | P:`platform.companies.read` or C:`company.users.read` | `status`, paging, `sort` |
| POST | `/companies/{companyId}/invitations/{id}/resend` · `/revoke` | P:`platform.companies.manage` or C:`company.users.manage` | Resend issues a new link and extends expiry; revoke deactivates the pending membership |
| POST | `/invitations/accept` | Me | `{token}` in the body; 404 unknown/revoked/other user's; 410 `INVITATION_EXPIRED`; repeat is idempotent |

### Roles & permissions

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/permissions?scope=COMPANY` | Me | Permission catalogue |
| GET | `/companies/{companyId}/roles` | C:`company.users.read` | System + custom roles |
| POST/PUT/DELETE | `/companies/{companyId}/roles[/{roleId}]` | C:`company.roles.manage` | Custom roles (COMPANY permissions only) |
| GET/PUT | `/platform/users/{userId}/roles` | P:`platform.roles.manage` | Platform role assignment; last SUPER_ADMIN cannot be removed (409 `LAST_ADMIN`) — implemented |
| GET | `/platform/roles` | P:`platform.roles.manage` | Platform roles with permissions — implemented |

### Product catalogue (implemented)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/products` | P:`platform.products.manage` | Register (DRAFT): `code`, `name`, `description?`, `appUrl`, `apiBaseUrl?`, `apiAudience?` |
| GET | `/products` | P:`platform.products.read` | `q` (code/name prefix), `status`, paging, `sort=name\|code\|createdAt\|status` |
| GET | `/products/config-schema` | P:`platform.products.read` | JSON Schema for configuration versions |
| GET | `/products/{productId}` | P:`platform.products.read` | Details incl. `currentVersionNo`, `ETag` |
| PATCH | `/products/{productId}` | P:`platform.products.manage` | Omitted fields unchanged, `""` clears optional ones; `code` immutable; `If-Match` |
| POST | `/products/{productId}/activate` · `/retire` | P:`platform.products.manage` | Lifecycle; activate needs a published version (422 otherwise) |
| POST | `/products/{productId}/versions` | P:`platform.products.manage` | `{config, notes?}` → DRAFT version N; 400 with `errors[]` (field = JSON path) if invalid; 409 if identical to the latest |
| GET | `/products/{productId}/versions` · `/{versionNo}` | P:`platform.products.read` | History (without bodies) / one version with `config` |
| POST | `/products/{productId}/versions/{versionNo}/publish` | P:`platform.products.manage` | Make current (rollback if older) |
| DELETE | `/products/{productId}/versions/{versionNo}` | P:`platform.products.manage` | Drafts only (409 for published) |
| POST | `/products/{productId}/features` | P:`platform.products.manage` | `{code, name, description?, type, unit?, resetPeriod?}` |
| GET | `/products/{productId}/features` | P:`platform.products.read` | `status` filter |
| PATCH | `/products/{productId}/features/{featureId}` | P:`platform.products.manage` | Rename/describe only |
| POST | `/products/{productId}/features/{featureId}/deprecate` · `/reactivate` | P:`platform.products.manage` | Deprecated features cannot be added to new plans |

Configuration (schema version 1) example:

```json
{
  "schemaVersion": 1,
  "launch": { "path": "/app/{companyCode}", "openInNewTab": true },
  "provisioning": { "mode": "SYNC", "path": "/provisioning/v1/tenants/{companyId}", "timeoutSeconds": 30, "maxAttempts": 8 },
  "tenantSettings": [ { "key": "region", "type": "enum", "label": "Data region", "options": ["in", "eu"], "default": "in" } ],
  "metadata": { "owner": "inventory-team" }
}
```

### Plans (implemented)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/plans` | P:`platform.plans.manage` | `{code, plan: {name, description?, billingPeriod (MONTHLY\|QUARTERLY\|HALF_YEARLY\|ANNUAL), trialDays, prices[{currency, amountMinor}], products[{productId, features[{code, enabled?, limit?}]}]}}` → DRAFT |
| GET | `/plans` · `/plans/{planId}` | P:`platform.plans.read` | List (`status`, `q`, paging, `sort`) / detail with resolved products and features |
| PUT | `/plans/{planId}` | P:`platform.plans.manage` | Replace a DRAFT's content (409 otherwise) |
| POST | `/plans/{planId}/publish` · `/archive` | P:`platform.plans.manage` | Freeze (needs a price, active products and features) / stop new assignments |
| POST | `/plans/{planId}/copy` | P:`platform.plans.manage` | `{code, name?}` → new DRAFT with the same content |

### Subscriptions (implemented)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/companies/{companyId}/subscriptions` | P:`platform.subscriptions.manage` | `{planId, currency, startTrial}` → TRIAL or ACTIVE |
| GET | `/companies/{companyId}/subscriptions` · `/{id}` | P:`platform.subscriptions.read` or C:`company.subscription.read` | List / detail with history, `graceUntil`, `dataRetentionUntil` |
| POST | `…/subscriptions/{id}/activate` | P:`platform.subscriptions.manage` | TRIAL → ACTIVE (first paid period starts) |
| POST | `…/subscriptions/{id}/renew` | P:`platform.subscriptions.manage` | Next period paid: ACTIVE/PAST_DUE/SUSPENDED → ACTIVE; applies scheduled downgrade |
| POST | `…/subscriptions/{id}/change-plan` | P:`platform.subscriptions.manage` | `{planId}`: upgrade now / downgrade at period end |
| POST | `…/subscriptions/{id}/cancel` | P:`platform.subscriptions.manage` | `{mode: IMMEDIATE\|PERIOD_END, reason?}` |
| POST | `…/subscriptions/{id}/resume` · `/suspend` | P:`platform.subscriptions.manage` | Revoke a scheduled cancellation / manual suspension |
| GET | `/platform/subscriptions` | P:`platform.subscriptions.read` | `status`, `companyId`, `planId`, paging, `sort=createdAt\|currentPeriodEnd\|status` |

Invalid transitions return 422 `INVALID_STATE_TRANSITION`. Timed transitions run automatically
(grace period 7 days, suspended expiry 90 days, data retention 90 days — configurable).

### Entitlements & usage

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/companies/{companyId}/entitlements` | P:`platform.subscriptions.read` or C:`company.usage.read` | Products with access status, feature limits and current usage — implemented |
| GET | `/service/access?companyId&userSubject&featureCode` | S (product service token) | Access of a company (and, with `userSubject`, of that user) to the **calling** product or one of its features. Always 200: `{allowed, reason, productCode, companyId, userId, roles, entitlementStatus, limit, used, remaining}`; reasons `OK, COMPANY_INACTIVE, NO_ENTITLEMENT, SUSPENDED, FEATURE_NOT_INCLUDED, LIMIT_REACHED, UNKNOWN_USER, USER_INACTIVE, NOT_A_MEMBER, NOT_PERMITTED` — implemented |
| POST | `/service/usage/consume` · `/service/usage/release` | S | `{companyId, featureCode, quantity (1–1,000,000), idempotencyKey (≤ 80, [A-Za-z0-9_.:-])}` → `{used, limit, remaining, replayed}`; 409 `LIMIT_EXCEEDED`, 403 `ENTITLEMENT_REQUIRED` — implemented |

S = client-credentials token of a product service (`client_id` claim) registered as the product's
`serviceClientId`. Service tokens are refused (403) on every other endpoint and user tokens on
`/service/**`; an unregistered or retired client gets 403 `FORBIDDEN`.

### Provisioning

| Method | Path | Auth | Purpose |
|---|---|---|---|
All implemented in Phase 7.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/platform/provisioning-jobs` | P:`platform.provisioning.read` | `status` (PENDING, RUNNING, AWAITING_CALLBACK, SUCCEEDED, FAILED, CANCELLED), `operation` (PROVISION, DEPROVISION), `companyId`, `productId`, paging, `sort=createdAt\|updatedAt\|status` (default newest first) |
| GET | `/platform/provisioning-jobs/{id}` | P:`platform.provisioning.read` | attempts, maxAttempts, nextAttemptAt, callbackDeadline, configVersionNo, lastHttpStatus, lastError |
| POST | `/platform/provisioning-jobs/{id}/retry` | P:`platform.provisioning.manage` | FAILED → PENDING with a fresh set of attempts; only the latest job of a company/product (else 409); 422 otherwise |
| POST | `/platform/provisioning-jobs/{id}/cancel` | P:`platform.provisioning.manage` | `{reason?}`; PENDING or AWAITING_CALLBACK only (422 otherwise) |
| POST | `/service/provisioning/jobs/{jobId}/callback` | S | `{outcome: SUCCEEDED\|FAILED, message?}` → 204; a job of another product → 404; repeated success → 204; completed job → 409 |
| GET | `/companies/{companyId}/products` | P:`platform.companies.read` or C:`company.products.launch` | Entitled ACTIVE products: `{productId, productCode, productName, entitlementStatus, validUntil, provisioningState, launchUrl, openInNewTab}`; `launchUrl` only when usable now |

Product registration and update accept `serviceClientId` (unique; empty string clears it on PATCH).

### Billing (implemented)

Invoices are created, numbered and emailed by Razorpay (D-3/D-4); Core stores references, amounts
and states. Amounts are in minor units (paise). When `RAZORPAY_KEY_ID`/`RAZORPAY_KEY_SECRET` are not
set, writes answer 503 `BILLING_DISABLED`; reads still work.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/billing/status` | any signed-in user | `{enabled, webhooksEnabled, gateway}` |
| GET | `/companies/{companyId}/invoices` | P:`platform.subscriptions.read` or C:`company.billing.read` | Billing history, newest first (paged; `status` filter) |
| GET | `/companies/{companyId}/invoices/{id}` | same | Invoice with payments. `paymentUrl` (Razorpay hosted page) only for open invoices and for staff or C:`company.billing.manage`; `lastError` only for staff |
| POST | `/companies/{companyId}/subscriptions/{subscriptionId}/invoices` | P:`platform.subscriptions.manage` | Issue the invoice for the subscription's next period now → 201. 409 `CONFLICT` if that period already has an open or paid invoice; 422 if the subscription has ended; 400 for a free plan or a company without a billing/primary contact email; 502 `PAYMENT_GATEWAY_ERROR` if Razorpay refuses (invoice kept as ISSUE_FAILED) |
| POST | `/companies/{companyId}/invoices/{id}/cancel` | P:`platform.subscriptions.manage` | Cancel an ISSUED/PARTIALLY_PAID invoice at Razorpay and in Core; other states → 422; paid meanwhile at Razorpay → 409 |
| POST | `/companies/{companyId}/invoices/{id}/refresh` | P:`platform.subscriptions.manage` | Fetch the invoice from Razorpay and apply its state (recovers a missed webhook) |
| GET | `/platform/invoices` | P:`platform.subscriptions.read` | All invoices (`status`, `companyId`, paging, `sort` by createdAt/periodStart) |
| POST | `/webhooks/payments/razorpay` | `X-Razorpay-Signature` (no user auth) | Razorpay webhook → 200 `{outcome: PROCESSED\|DUPLICATE\|IGNORED\|UNKNOWN_INVOICE}`; invalid signature → 401 (audited); missing `x-razorpay-event-id` → 400; webhook secret not set → 503 |

Invoice `status`: CREATING → ISSUED → PARTIALLY_PAID → PAID, or ISSUE_FAILED, CANCELLED, EXPIRED.
`paymentApplied` (after PAID): APPLIED (trial activated or period renewed), ALREADY_COVERED (the
period had already been renewed, e.g. manually), NOT_APPLICABLE (subscription cancelled/expired —
staff must refund in Razorpay).

### Audit, notifications, reporting

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/audit-logs` | P:`platform.audit.read` | Keyset search: `companyId`, `actorUserId`, `action` (prefix), `targetType`, `targetId`, `from`, `to`, `cursor`, `size` — implemented |
| GET | `/companies/{companyId}/audit-logs` | P:`platform.audit.read` or C:`company.audit.read` | Company-scoped audit trail — implemented |
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
{
  "code": "acme",
  "legalName": "Acme Industries Pvt Ltd",
  "displayName": "Acme",
  "country": "IN",
  "timeZone": "Asia/Kolkata",
  "primaryContact": { "name": "R. Sharma", "email": "owner@acme.example" },
  "initialAdmin": { "email": "admin@acme.example", "firstName": "Riya", "lastName": "Sharma" }
}
→ 201 Created, Location: /api/v1/companies/01a1…, ETag: "0"
{ "id": "01a1…", "code": "acme", "legalName": "Acme Industries Pvt Ltd", "displayName": "Acme",
  "status": "ACTIVE", "country": "IN", "taxId": null, "createdAt": "…", "updatedAt": "…", "version": 0 }
```

The initial admin's invitation is listed at `GET /companies/{id}/invitations`. Subscriptions are
added in Phase 6. `Idempotency-Key` is planned (BACKLOG C-10); duplicate codes are already 409.

Company creation, admin membership/invitation, subscriptions, entitlements and outbox events
commit in one transaction; provisioning calls happen asynchronously after commit.
