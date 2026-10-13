# ADR-0008: Product integration — client identity for services, DB-backed provisioning jobs

- Status: Accepted · Date: 2026-10-13

## Context
Phase 7 connects independent product services to Core in both directions: Core must set up
(and later remove) companies in products, and products must check access and report usage. ADR-0004
ruled out a message broker. The original design sketched OAuth scopes per endpoint and a generic
`outbox_event` table.

## Decision
1. **Service authorization by client identity.** A product calls Core with its own Keycloak
   client (client credentials, audience `occpl-core`). Core recognises service tokens by the
   `client_id` claim, gives them only the `SERVICE` authority (service API only) and maps the
   client to exactly one product (`product.service_client_id`). Every service request acts on that
   product only. Core calls products with one client, `occpl-core-services`, whose token carries
   each product's audience; products accept provisioning only from that client. Per-endpoint scopes
   were not adopted: the service API is small and every endpoint is product-scoped anyway.
2. **Provisioning jobs as rows, created in the business transaction.** Entitlement changes are
   handled synchronously in the subscription change's transaction and create `provisioning_job`
   rows there, so jobs exist exactly when the change commits. A scheduled runner on every instance
   claims due jobs with `SKIP LOCKED` and a lease, performs the HTTP call outside any transaction
   and records the result under a row lock if the attempt is still current. No separate outbox
   table is needed while all consumers are in-process.
3. **Removal is delayed** by the data-retention period (D-5) and cancelled on re-entitlement.

## Consequences
+ No broker, no extra infrastructure; at-least-once delivery with idempotent product endpoints
  (job id as `Idempotency-Key`); horizontal scaling of runners.
+ A product cannot touch another product's data even with a valid token.
− Polling interval (5 s) adds latency to provisioning; acceptable for tenant setup.
− Adding a product requires two Keycloak changes (its client, and an audience mapper on
  `occpl-core-services`); documented in DEPLOYMENT.md.
− If consumers ever leave the process, a real outbox/broker is needed (revisit ADR-0004).
