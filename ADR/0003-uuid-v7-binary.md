# ADR-0003: UUID v7 identifiers stored as BINARY(16)

- Status: Accepted · Date: 2026-10-13

## Context
Need globally unique, non-guessable-ish, application-generated ids (useful for idempotent
creates and future data moves) without the InnoDB index fragmentation of random UUID v4.

## Decision
Generate UUID v7 in the application; store as `BINARY(16)`; expose as canonical string in APIs.
Hibernate mapping via `@JdbcTypeCode(SqlTypes.BINARY)` / UUID type. Ids are never used as an
authorization mechanism.

## Consequences
+ Time-ordered inserts, 16-byte keys, safe to expose.
− Less readable in raw SQL (use `BIN_TO_UUID`). Timestamp leakage from v7 is acceptable.
