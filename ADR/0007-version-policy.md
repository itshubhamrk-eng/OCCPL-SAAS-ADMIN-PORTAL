# ADR-0007: Version policy — latest stable, LTS where a choice exists

- Status: Accepted · Date: 2026-10-13

## Context
OCCPL asked to use the latest versions of Java, Angular, Spring Boot and MySQL (superseding the
original Java 21 / Spring Boot 3.x / MySQL 8 mandate). For Java and MySQL the newest release is a
short-support feature/innovation release, while a newer LTS line also exists.

## Decision
| Component | Chosen | Why |
|---|---|---|
| Java | 25 LTS | Java 27 is newest but non-LTS (≈6 months of updates); 25 is the latest LTS |
| Spring Boot | 4.1.1 | Latest GA |
| Angular | 22.2 | Latest stable |
| MySQL | 9.7 LTS | 26.7 is an innovation release with short support; 9.7 is the latest LTS |
| Keycloak | 26.8.1 | Latest |

Dependabot proposes updates weekly; patch/minor updates are taken continuously, LTS-to-LTS
upgrades are planned. Switching Java to 27 or MySQL to the innovation track requires changing one
version property/image tag and re-running CI.

## Consequences
+ Current platform features and security fixes; long support windows in production.
− Newest ecosystem (Boot 4, Jackson 3, Flyway on MySQL 9.7) carries some compatibility risk
  (PROJECT.md R-1, R-2), mitigated by integration tests on the real database.
