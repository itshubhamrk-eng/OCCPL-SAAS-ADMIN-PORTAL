# Security Design

Status: Design approved (D-2: self-hosted Keycloak, the secure option). Phase 2 implemented the
resource-server baseline, error handling, headers and CORS; identity mapping and authorization
arrive in Phase 3.

## 1. Authentication

- **IdP**: Keycloak 26.8, realm `occpl` (ADR-0002), self-hosted so credentials and identity data stay
  in OCCPL-controlled infrastructure. The Core Platform never stores passwords or
  issues end-user tokens.
- **Browser (Angular)**: Authorization Code + PKCE, public client `occpl-admin-portal`.
  Access token lifetime 5 min, refresh-token rotation enabled, SSO session idle 30 min /
  max 10 h (configurable). Tokens held in memory only. MFA (TOTP/WebAuthn) **required** for
  platform roles, optional per company (company setting) for company users.
- **Logout**: RP-initiated logout at the IdP + local state clear; Core also exposes the
  user's sessions to Super Admin via the IdP admin API for forced logout.
- **Core API**: OAuth2 resource server; validates signature (JWKS, cached with rotation), `iss`,
  `aud=occpl-core`, `exp`/`nbf` (≤ 60 s clock skew), `azp` in allowlist.
- **Service-to-service**: confidential clients with client-credentials; per-product audience;
  scopes `entitlements:read`, `usage:write`, `provisioning:callback`.
- **Invitations**: Core creates the IdP user (disabled credentials) via the Keycloak Admin API
  using a dedicated least-privilege service account (`manage-users` on the realm only), then
  sends an invitation link containing a single-use random token (only its SHA-256 hash is
  stored, 7-day expiry). Acceptance triggers IdP "set password / verify email" actions.

## 2. Authorization model

```
User ──(platform_role_assignment)──► PLATFORM roles ──► PLATFORM permissions
User ──(company_membership: ACTIVE)──► membership_role ──► COMPANY roles ──► COMPANY permissions
Company ──(entitlements)──► products / features / limits
```

Effective access to a company-scoped operation requires **all** of:

1. Authenticated, platform `user.status = ACTIVE`.
2. Either a PLATFORM permission covering the operation (e.g. `platform.companies.manage`), **or**
   an ACTIVE membership in the target company with the required COMPANY permission.
3. Company status allows the operation (SUSPENDED companies: read-only for Company Admin,
   no product access; DEACTIVATED: no access except platform).
4. For product access: an ACTIVE/GRACE entitlement for the product (and feature).

### Initial roles (seeded, extensible)

| Role | Scope | Notes |
|---|---|---|
| SUPER_ADMIN | PLATFORM | All platform permissions. Assigned only by another SUPER_ADMIN; at least one must remain. |
| PLATFORM_SUPPORT | PLATFORM | Read-only across companies (future) |
| COMPANY_ADMIN | COMPANY | All company permissions |
| COMPANY_MANAGER | COMPANY | Manage users (cannot grant COMPANY_ADMIN), view subscription |
| COMPANY_USER | COMPANY | Launch entitled products, view own profile |

Company Admins may create **custom company roles** composed only of COMPANY-scope
permissions. The API rejects any PLATFORM permission, any role from another company, and any
grant of a permission the granting user does not hold (no privilege escalation by delegation).
The last ACTIVE COMPANY_ADMIN of a company cannot be removed or demoted.

### Permission examples

Platform: `platform.companies.read|manage`, `platform.users.read|manage`,
`platform.roles.manage`, `platform.products.read|manage`, `platform.plans.read|manage`,
`platform.subscriptions.read|manage`, `platform.provisioning.read|manage`,
`platform.audit.read`, `platform.reports.read`, `platform.settings.manage`.

Company: `company.profile.read|manage`, `company.users.read|manage`, `company.roles.assign`,
`company.roles.manage`, `company.subscription.read`, `company.billing.read|manage`,
`company.usage.read`, `company.audit.read`, `company.products.launch`.

The permission catalogue is seeded by migrations; API_SPEC.md references these codes.

## 3. Tenant isolation

- The tenant is **always** taken from the URL path segment `/companies/{companyId}` and then
  **verified** against the caller's membership on the server. IDs in bodies/query strings are
  never trusted for authorization. Product-scoped routes resolve the company from the
  resource's own `company_id`, never from the request.
- `TenantContext` is built per request (no thread-local leakage across async boundaries;
  explicit propagation to async tasks).
- Repositories for tenant-owned tables expose only `findByCompanyIdAnd…` methods; a module test
  forbids unscoped `findById` on tenant-owned repositories outside platform services.
- Lookups of a resource belonging to another tenant return **404** (not 403) to avoid
  enumeration.
- Mandatory negative tests (BACKLOG H-2): for every company-scoped endpoint, a user of company A
  receives 404/403 when addressing company B's resources by changing path/query/body IDs, and a
  COMPANY_USER cannot call COMPANY_ADMIN operations.

### Freshness of authorization data

JWTs carry identity only (`sub`, `email`, `email_verified`, `amr`). User status, platform roles,
memberships, company status (and later subscription status and entitlements) are read from the
Core database on every request — currently without a cache, using indexed lookups — so a disabled
user, suspended membership or suspended company loses access on the next request, not at token
expiry. A Redis cache (≤ 30 s TTL, evicted on change events) will be added only if load tests show
the need.

## 4. Web and API protection

| Control | Implementation |
|---|---|
| CSRF | Bearer tokens in `Authorization` header, no auth cookies → CSRF disabled for the API with this justification. If a BFF/cookie flow is ever introduced, enable SameSite=strict cookies + CSRF tokens. |
| CORS | Explicit origin allowlist per environment; no wildcard with credentials. |
| Headers | HSTS, `Content-Security-Policy` (self + IdP origin), `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors 'none'`, `Permissions-Policy`. |
| Rate limiting | Nginx per-IP coarse limit; Redis token bucket per user/client and per route class in Core (stricter for invitations, auth-adjacent and webhook endpoints). 429 with `Retry-After`. |
| Input validation | Bean Validation on all DTOs, size limits on strings/collections, request body max size, JSON schema validation for product configuration. |
| Injection | JPA parameter binding / Criteria only; dynamic sort fields mapped from allowlists. |
| Output | Angular auto-escaping; no `innerHTML` with untrusted data; API returns JSON only. |
| Errors | RFC 9457 problem details; no stack traces, SQL or internal IDs of other tenants. |
| Webhooks | HMAC signature verification with constant-time compare, timestamp tolerance, event-id idempotency, raw body retained only as a hash. |
| Uploads | None initially. If added: type sniffing, size limits, AV scan, object storage, never served from app origin. |

### Implemented in Phase 2 (verified)

- JWT validation of signature (JWKS), issuer and audience `occpl-core`: unit/slice tests, plus a
  manual end-to-end check against Keycloak 26.8.1 (valid token accepted; missing audience, foreign
  issuer and tampered signature → 401).
- Local realm import (`infra/docker/keycloak/occpl-realm.json`): public PKCE (S256) client, no
  implicit or password grants, audience mapper, brute-force lockout, password policy
  `length(12)`, history 5, 5-minute access tokens, refresh-token rotation, email verification.
  MFA enforcement for platform roles is configured in Phase 3 together with role mapping.
- Portal Nginx: strict CSP (`script-src 'self'`, no inline scripts — Angular critical-CSS inlining
  is disabled for this reason), `frame-ancestors 'none'`, `nosniff`, no caching of `index.html` /
  `config.json`, unprivileged container user.

### Implemented in Phase 3 (verified)

- First-login rules, account-status errors and identity-takeover protection (see BACKLOG I-2).
- MFA for platform staff: Keycloak's browser flow (`occpl browser`) records Authentication Method
  References and the portal client emits the `amr` claim. Core grants platform permissions only when
  `amr` contains `otp` (configurable: `occpl.identity.mfa.accepted-amr`). When platform roles are
  granted, Core asks Keycloak (service account `occpl-core-admin`, realm-management
  `manage-users`/`view-users`/`query-users` only) to require authenticator setup. Verified end to end
  with real Keycloak login pages: password-only token → `mfaRequired: true`, no permissions; after
  enrolment and an OTP login → permissions granted.
- First Super Admin: `BOOTSTRAP_SUPER_ADMIN_EMAIL` grants SUPER_ADMIN on first login with that
  verified email **only while no Super Admin exists**; audited as `platform.super_admin.bootstrapped`.
  Clear the variable after initial setup.
- Company authorization via `@tenant.check`; cross-tenant ids return 404 with a body identical to a
  non-existent company. Suspended (and pending) companies allow only `*.read` permissions.
- Audit rows for role changes are written in the same transaction (`audit_log`). The application DB
  account still has UPDATE/DELETE on `audit_log` (database-wide grant); table-level revocation is
  scheduled for Phase 10 (DEPLOYMENT.md).
- Rate limiting fails open when Redis is unavailable; requests with an invalid token are rejected
  by the bearer filter before the rate limiter, so the Nginx per-IP limit is the control for
  invalid-token floods.

### Implemented in Phase 4 (verified)

- Company administration rules enforced server-side and covered by negative tests: a member can only
  grant, change, suspend or remove access within their own permissions (a manager cannot create,
  demote or suspend an admin); members cannot change their own membership; the last active Company
  Admin cannot be suspended, deactivated or demoted (row-locked check); company admins can change
  the display name but not the legal identity; invitations can be restricted to company email domains.
- Invitation tokens: 256-bit random, stored only as SHA-256, valid 7 days, rotated on resend,
  accepted only by the signed-in user the invitation was addressed to (otherwise 404), submitted in
  the request body (not the URL). New users prove email ownership through Keycloak's account-setup
  link before they can sign in. The accept URL carries the token as a query parameter; the portal
  must not log it (Phase 8: disable access logging for `/invitations/` in the portal's Nginx).
- Platform user disable takes effect on the next request in Core and is mirrored to Keycloak
  (account disabled, sessions ended); Super Admins and one's own account cannot be disabled.
- Email bodies (which can contain invitation links) are deleted from the outbox once sent.

### Implemented in Phase 5 (verified)

- Product configuration cannot carry secrets: setting/metadata names that look like secrets
  (secret, password, token, api key, private key, credential) and values that look like private keys
  or JWTs are rejected. Product service credentials live in Keycloak (client per product) and the
  secret store.
- Product URLs must use HTTPS (HTTP only for localhost) and may not embed credentials.
- Catalogue management requires `platform.products.manage`; Platform Support has read-only access.

### Implemented in Phase 6 (verified)

- Subscriptions and plans can only be changed with platform permissions; company members can read
  their own company's subscriptions and entitlements (`company.subscription.read`, `company.usage.read`)
  and get 404 for other companies.
- Entitlement decisions always apply company status (suspended/deactivated → no access) and read
  current data from the database, so suspension takes effect immediately.
- Usage limits are enforced atomically on the server; idempotency keys prevent double counting on retries.

## 5. Secrets and data protection

- No secrets in Git. Local dev uses `.env` (gitignored) with dev-only values; staging/prod use
  the host secret manager / environment injection (e.g. Vault, AWS SSM, or Docker secrets).
- TLS everywhere externally; TLS to MySQL/Redis in production.
- Encryption at rest: MySQL InnoDB tablespace encryption or encrypted volumes; encrypted
  backups.
- Least-privilege DB users (`occpl_migrator`, `occpl_app`), Redis ACL user, Keycloak service
  account scoped to the realm.
- Logging: never log passwords, tokens, authorization headers, payment payloads, full emails
  in debug dumps. A log-masking converter strips `Authorization`, `token`, `secret` fields.
- Personal data: export (JSON) and erasure (anonymization) workflows per D-10.
- Dependency scanning: OWASP Dependency-Check / GitHub Dependabot for Maven and npm; container
  image scanning (Trivy) in CI.

## 6. Audit

Every administrative mutation (company lifecycle, membership/role change, product/plan change,
subscription change, entitlement override, provisioning retry, login-as/impersonation if ever
added) writes an `audit_log` row in the same transaction: actor, company, action, target,
outcome, correlation id, sanitized before/after diff. Failed authorization attempts on
sensitive endpoints are logged (rate-limited) for anomaly detection.
