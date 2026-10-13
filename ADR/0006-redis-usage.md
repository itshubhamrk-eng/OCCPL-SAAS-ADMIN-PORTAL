# ADR-0006: Redis for authorization cache and rate limiting

- Status: Proposed · Date: 2026-10-13

## Context
Every request needs membership/permission resolution, and product services call entitlement
checks at high frequency. Rate limits must be shared across multiple Core replicas.

## Decision
Use Redis 8 (added in Phase 3, when the first cache/rate limit is implemented) for (a) short-TTL (≤ 30 s) caches of membership permissions and entitlements with
event-driven eviction, (b) distributed token-bucket rate limiting (Bucket4j or equivalent).
Redis is never the source of truth; Core degrades to DB reads if Redis is unavailable
(rate limiting fails open only for authenticated platform-internal traffic, closed otherwise —
to be finalized in Phase 3).

## Consequences
+ Lower DB load and consistent limits across replicas.
− One more component to operate; cache staleness bounded by TTL.
