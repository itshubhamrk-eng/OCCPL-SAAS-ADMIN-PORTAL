# Load tests (BACKLOG H-3)

k6 scenarios against Core on seeded data: 10,000 companies and 1,000,000 users. Company sizes
follow a Zipf distribution: most companies have fewer than 50 members, 20 have more than 5,000 and
the largest has 50,000. Every 20th user is also a member of a second company. Results:
[RESULTS.md](RESULTS.md).

**Only against disposable test environments.** The setup adds Keycloak clients with the password
grant and writes test data directly into the database.

## Run

```bash
# 1. Fresh stack with load-test settings (raised rate limits; everything else as in production)
cd infra/docker && docker compose down -v && cd ../..
docker compose -f infra/docker/docker-compose.yml -f load-tests/compose.loadtest.yml up -d --wait

# 2. Keycloak load-test clients, staff user with TOTP, product + plan through the API, seed, tokens
python3 load-tests/setup.py --companies 10000 --users 1000000 --sample 400

# 3. Scenarios (RATE scales all request rates; RATE=1 ≈ 105 requests/s)
k6 run -e RATE=1 -e DURATION=3m load-tests/k6/core.js
```

`tokens.json` (git-ignored) holds one-hour access tokens for 400 seeded users (the admins of the
20 largest companies plus random admins and members) and a platform staff token with MFA.

## Scenarios

| Scenario | Requests | SLO (PROJECT.md §2, provisional, D-6) |
|---|---|---|
| `me` | `GET /api/v1/me` (memberships, roles, permissions) | p95 < 300 ms |
| `company_reads` | company profile, entitlements, product launcher, member list pages | p95 < 300 ms |
| `member_search` | member search by email/name in the 20 largest companies | p95 < 300 ms |
| `entitlement_check` | service API `GET /service/access` with user and feature | p95 < 50 ms |
| `usage_consume` | service API `POST /service/usage/consume` (atomic counter + idempotency record) | p95 < 800 ms |
| `admin_writes` | company contact upsert by company admins (audited) | p95 < 800 ms |
| `platform_lists` | staff company list/search, subscriptions, audit log | p95 < 800 ms |

Error rate threshold everywhere: < 0.1 %.
