# Load test results (BACKLOG H-3)

Measured on 2026-10-14. All numbers come from the k6 summaries of the runs below; nothing is
extrapolated. **They describe a single 4-vCPU / 15 GB development VM that ran everything at once**
(Core, MySQL 9.7, Redis, Keycloak, the portal, the sample product *and* the k6 generator), so they
are a lower bound and a regression baseline, not a statement about production capacity. Production
sizing needs the same run on the production environment before launch (BACKLOG H-5).

## Data set

Seeded by `seed/seed_sql.py` in 69 s: 10,000 companies, 1,000,001 users, 1,049,996 memberships
(every 20th user in two companies), one ACTIVE subscription and entitlement per company. Company
sizes follow Zipf: 7,705 companies have fewer than 50 members, 20 have more than 5,000, the largest
has 50,000. 400 seeded users (admins of the 20 largest companies, random admins and members) and
one MFA staff user had real Keycloak logins. Core: one instance, default settings (HikariCP pool 20,
Tomcat defaults), rate limiting on with raised limits (`compose.loadtest.yml`).

## Results

p95 / p99 in milliseconds per scenario group (see README.md). RATE 1 ≈ 105 requests/s with the mix
me 19 % · company reads 24 % · member search 4 % · entitlement checks 38 % · usage consume 10 % ·
admin writes 3 % · platform lists 3 %.

| Run | Achieved | Errors | Reads (SLO p95 < 300) | Member search (< 300) | Entitlement check (< 50) | Writes (< 800) | Platform lists (< 800) |
|---|---|---|---|---|---|---|---|
| RATE 1, before the search fix | 105 req/s | 0 % | 60 / 282 | **507** / 702 ✗ | 39 / 176 | 55 / 246 | 69 / 177 |
| RATE 1 | 105 req/s | 0 % | 44 / 247 | 54 / 220 | 33 / 226 | 46 / 268 | 55 / 220 |
| RATE 2 | 210 req/s | 0 % | 51 / 109 | 62 / 97 | 41 / 69 | 50 / 82 | 69 / 117 |
| RATE 3 (315 offered) | 256 req/s | 1.4 % | 3,974 / 6,062 ✗ | 4,796 ✗ | 7,837 ✗ | 8,754 ✗ | 4,081 |
| RATE 4 (420 offered) | 291 req/s | 1.8 % | 5,069 / 6,858 ✗ | 5,089 ✗ | 10,056 ✗ | 9,050 ✗ | 5,090 |

Average CPU (docker stats, 100 % = one vCPU): RATE 1 — Core 117 %, MySQL 58 %; RATE 2 — Core
146 %, MySQL 113 %; RATE 3 — Core 209 %, MySQL 118 %, plus the k6 generator on the same 4 vCPUs.
Core's memory stayed below 1.1 GB.

**Result on this machine: all provisional SLOs are met up to 210 requests/s with no errors.**
Beyond that the VM's CPUs are saturated and requests queue for database connections.

## What the test changed

1. **Member search in large companies** missed its SLO (p95 507 ms). The query walked all
   50,000 memberships of the company and joined user and profile for each, twice (count + page).
   It now starts from the users matching the prefix (unique email index and a new
   `user_profile.display_name` index, migration `V202610141000`) when the term has at least three
   characters: 214 ms → 4 ms for the count in `EXPLAIN ANALYZE`, p95 54 ms under load. Shorter terms
   keep the company-driven plan (a one-letter prefix matches a large share of all users).
2. **Overload behaviour.** When the pool was exhausted, requests failed with HTTP 500 and a stack
   trace each (thousands of log lines per minute), partly from inside the security filter. Pool
   exhaustion is now answered **503 with `Retry-After: 2`** and one warning line, both in the
   controller advice and in a filter in front of Spring Security (`OverloadFilter`). The RATE 3
   and 4 rows were measured after this change: every error was such a 503.

## Not covered (open)

- Production-like hardware, several Core instances behind a load balancer, separate MySQL host,
  generator on its own machine (H-5). Expect the limit above to move with CPU; the SLO-relevant
  latencies at RATE 1–2 are dominated by a few database round trips per request.
- Keycloak login throughput: tokens were issued once before the runs (one-hour lifetime).
- Soak test (hours) and memory growth over time; the runs lasted 2–3 minutes each.
- Usage counters under heavy contention on one company (the atomic UPDATE serialises per counter;
  verified for correctness in integration tests, not for throughput).
