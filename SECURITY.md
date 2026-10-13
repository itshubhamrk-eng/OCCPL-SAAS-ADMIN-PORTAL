# Security Design

Status: Phase 1 proposal.

## 1. Authentication

- **IdP**: Keycloak 26.x, realm `occpl` (ADR-0002). The Core Platform never stores passwords or
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
- Repositories for tenant-owned tables expose only `findByCompanyIdAnd…` methods; an ArchUnit
  rule forbids unscoped `findById` on tenant-owned repositories outside platform services.
- Lookups of a resource belonging to another tenant return **404** (not 403) to avoid
  enumeration.
- Mandatory negative tests (BACKLOG H-2): for every company-scoped endpoint, a user of company A
  receives 404/403 when addressing company B's resources by changing path/query/body IDs, and a
  COMPANY_USER cannot call COMPANY_ADMIN operations.

### Freshness of authorization data

JWTs carry identity only (`sub`, `email`, platform-role hint at most). Memberships, roles,
company status, subscription status and entitlements are read from Core per request and
cached for ≤ 30 s in Redis with explicit eviction on change events. A suspended company or
membership therefore loses access within the cache TTL, not at token expiry.

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
