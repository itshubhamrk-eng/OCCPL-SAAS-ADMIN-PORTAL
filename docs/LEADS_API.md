# Leads API

Register and manage leads (prospective customers) on the platform side. For the full inventory and
conventions see [API_SPEC.md](../API_SPEC.md); the generated OpenAPI document (`/v3/api-docs`) is the
authoritative contract.

- **Base URL:** `<core-platform host>/api/v1` (dev portal config: `http://localhost:8080/api/v1`)
- **Format:** JSON (`Content-Type: application/json`), camelCase, ISO-8601 UTC timestamps, UUID ids.

## Authentication

Every endpoint needs a Keycloak access token for a **platform staff user**:

```
Authorization: Bearer <access token>
```

| Permission | Allows | Roles that have it |
|---|---|---|
| `platform.leads.read` | list, search, get | Super Admin, Platform Support |
| `platform.leads.manage` | register, edit, change status | Super Admin |

> **A public website cannot call these endpoints directly.** They are staff-only, and a browser
> must never hold a staff token. To feed website enquiries into leads, the website needs either a
> server-side call using a dedicated staff/service account, or a separate public, rate-limited intake
> endpoint (not built yet). See "Connecting the website" below.

## Lead object

| Field | Type | Notes |
|---|---|---|
| `id` | uuid | |
| `companyName` | string | required, ≤ 200 |
| `contactName` | string | required, ≤ 200 |
| `email` | string | required, valid email, stored lower-case |
| `phone` | string \| null | digits, space, `+ ( ) - .`, 5–32 chars |
| `productInterest` | string \| null | free text, ≤ 200 |
| `source` | string \| null | free text, ≤ 100 (e.g. `Website`) |
| `status` | enum | `NEW`, `CONTACTED`, `QUALIFIED`, `CONVERTED`, `LOST` |
| `notes` | string \| null | ≤ 2000 |
| `createdAt`, `updatedAt` | string | ISO-8601 UTC |
| `version` | number | also returned as the `ETag` header |

## Endpoints

### POST /leads — register
Permission `platform.leads.manage`. Body: `companyName`, `contactName`, `email` (required);
`phone`, `productInterest`, `source`, `notes` (optional). Starts as `NEW`, queues a confirmation email
to the lead, and returns `201` with `Location` and `ETag`.

```bash
curl -X POST "$BASE/leads" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"companyName":"Acme Pvt Ltd","contactName":"Priya Shah","email":"priya@acme.example",
       "phone":"+91 98765 43210","productInterest":"Courier","source":"Website"}'
```

### GET /leads — search
Permission `platform.leads.read`. Query: `q` (prefix of company name, contact name or email),
`status`, `page` (default 0), `size` (default 20, max 100), `sort` (repeatable; `companyName`,
`contactName`, `status`, `createdAt`; default `createdAt,desc`). Response:
`{ "items": [Lead], "page": 0, "size": 20, "totalItems": 1, "totalPages": 1 }`.

### GET /leads/{leadId}
Permission `platform.leads.read`. Returns the lead with an `ETag`; `404` if unknown.

### PATCH /leads/{leadId} — edit
Permission `platform.leads.manage`. Send only fields to change; `""` clears an optional field.
Optional `If-Match: "<version>"` returns `412` if the lead changed meanwhile. Status is not changed here.

### POST /leads/{leadId}/status — change status
Permission `platform.leads.manage`. Body: `{ "status": "CONTACTED", "reason": "optional, ≤ 500" }`.

| From | Allowed next |
|---|---|
| NEW | CONTACTED, QUALIFIED, LOST |
| CONTACTED | QUALIFIED, LOST |
| QUALIFIED | CONVERTED, LOST |
| LOST | NEW (reopen) |
| CONVERTED | none (final) |

Any other move returns `422` with code `INVALID_STATE_TRANSITION`.

## Errors

RFC 9457 problem JSON with a stable `code`:

```json
{ "status": 400, "code": "VALIDATION_ERROR", "detail": "One or more fields are invalid.",
  "errors": [ { "field": "email", "code": "Email", "message": "must be a well-formed email address" } ] }
```

`400` validation, `401` missing/invalid token, `403` lacks permission, `404` not found, `412`
stale `If-Match`, `422` invalid status move, `429` rate limited. Every response carries
`X-Correlation-Id`; include it when reporting problems.

## Public website form: POST /public/leads

For the website's contact form. **No sign-in and no token.** Creates a `NEW` lead with `source`
`Website` and emails the sender a confirmation.

```bash
curl -X POST "$BASE/public/leads" -H "Content-Type: application/json" \
  -d '{"companyName":"Acme Pvt Ltd","contactName":"Priya Shah","email":"priya@acme.example",
       "phone":"+91 98765 43210","productInterest":"Courier","message":"Please call me"}'
```

| Field | Notes |
|---|---|
| `companyName`, `contactName`, `email` | required (same limits as above) |
| `phone`, `productInterest` | optional |
| `message` | optional, ≤ 2000; stored as the lead's notes |
| `website` | **honeypot**: add a hidden input with this name and leave it empty. Submissions that fill it are silently discarded (still answered `202`) |

Responses: `202 {"received": true}` on success (no lead details are returned), `400` validation,
`429` after 5 submissions per minute from one IP (`Retry-After` header).

**Setup on the server:** the website's origin must be listed in `occpl.security.cors.allowed-origins`
(otherwise browsers block the call), and the API must run behind the reverse proxy so the client IP
is real. Call it from the browser or from your website backend; no secret is involved.

## Calling the staff endpoints from the website backend

`POST /leads` and the rest need a staff token (see Authentication). Use them only from a server,
never a browser. Staff users currently require MFA before platform permissions apply, so a
non-interactive account needs a decision first. For a plain contact form, prefer `/public/leads`.
