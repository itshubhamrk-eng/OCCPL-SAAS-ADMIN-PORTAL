# ADR-0002: Keycloak as OpenID Connect identity provider

- Status: Accepted (D-2: "secure" option — self-hosted) · Date: 2026-10-13

## Context
Must not build password storage/token issuance. Need SSO across Core portal and product
services, MFA, service clients (client credentials), admin API for invitations, self-hosting
option on Ubuntu, no per-MAU cost at 1M users.

## Decision
Keycloak 26.8, single realm `occpl`. Public PKCE client for the Angular app; one confidential
client per product service; Core uses a least-privilege service account for user management.
Core maps IdP `sub` to its own `user` via `user_identity(provider, subject)` so the IdP can be
replaced later. Authorization data (memberships, roles, entitlements) lives in Core, not in
token claims.

## Consequences
+ Standards-based, free, feature-rich, replaceable behind OIDC.
− OCCPL operates and upgrades Keycloak (HA, DB, backups). A managed IdP removes this at a cost.
