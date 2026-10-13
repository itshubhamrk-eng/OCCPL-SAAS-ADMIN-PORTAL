# ADR-0006: Redis for authorization cache and rate limiting

- Status: Proposed · Date: 2026-10-13

## Context
Every request needs membership/permission resolution, and product services call entitlement
checks at high frequency. Rate limits must be shared across multiple Core replicas.

## Decision
Use Redis 8 (added in Phase 3, when the first cache/rate limit is implemented) for (a) short-TTL (≤ 30 s) caches of membership permissions and entitlements with
event-driven eviction, (b) distributed token-bucket rate limiting (Bucket4j or equivalent).
Redis is never the source of truth. Rate limiting **fails open** when Redis is unavailable (decided
in Phase 3): API availability is preferred, Nginx keeps a coarse per-IP limit, and failures are
counted in the `occpl.ratelimit.errors` metric for alerting. The authorization cache (a) is deferred
until load tests show the need; Phase 3 reads authorization data from MySQL on every request.

## Consequences
+ Lower DB load and consistent limits across replicas.
− One more component to operate; cache staleness bounded by TTL.
