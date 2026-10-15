# ADR-0004: Transactional outbox + DB-backed jobs; no message broker or Kubernetes initially

- Status: Accepted · Date: 2026-10-13

## Context
Provisioning and notifications must survive crashes and retries. Event volume at launch is low
(company/subscription changes, not high-frequency streams). Team is small.

## Decision
Use an `outbox_event` table written in the business transaction and a DB-backed
`provisioning_job` queue processed by scheduled workers (ShedLock / `SELECT … FOR UPDATE SKIP
LOCKED`). Deploy with Docker Compose / VMs. Revisit a broker (RabbitMQ/Kafka) when outbox lag or
consumer count is a measured problem, and Kubernetes when node count or autoscaling needs justify.

## Consequences
+ Exactly-once state change with at-least-once delivery; one less system to operate.
− Polling latency (seconds) and DB load from workers; acceptable at current scale.

## Amendment (2026-10-14, Phase 10)
As implemented there is no generic `outbox_event` table and no ShedLock: each module keeps its own
outbox (`notification` for email, `provisioning_job` for product calls), written in the business
transaction and claimed with `SELECT … FOR UPDATE SKIP LOCKED`. The other scheduled jobs
(subscription sweep, renewal invoicing, invitation expiry) lock and re-check rows or rely on unique
constraints. All of them are therefore safe with several Core instances, which production runs
(two EC2 instances, ADR-0011) without leader election. Spring Modulith's event publication
registry is not used.
