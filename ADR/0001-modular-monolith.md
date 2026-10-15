# ADR-0001: Modular monolith for the Core Platform

- Status: Accepted · Date: 2026-10-13

## Context
Twelve platform capabilities with shared transactional needs (e.g. company creation + admin
membership + subscription + entitlements must commit atomically). Small team, early stage.
Products differ completely and must deploy independently.

## Decision
One Spring Boot application for all Core modules, packaged by module with `api`/`web`/`internal`
sub-packages. Boundaries verified in CI by Spring Modulith (`ApplicationModules.verify()`); a module's
`api` package is its only named interface. Cross-module interaction via `api` facades and in-process domain events; no
cross-module JPA associations; each module writes only its own tables. Products are separate
services.

## Consequences
+ Simple deployment, local transactions, low ops cost, fast refactoring.
+ Modules can later be extracted (API → REST, events → broker, tables move with module).
− Requires discipline; mitigated by automated boundary tests.
− Whole Core scales as one unit until a module is extracted.
