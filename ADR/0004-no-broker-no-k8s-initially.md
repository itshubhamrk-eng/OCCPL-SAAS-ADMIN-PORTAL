# ADR-0004: Transactional outbox + DB-backed jobs; no message broker or Kubernetes initially

- Status: Proposed · Date: 2026-10-13

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
