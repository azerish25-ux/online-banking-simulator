# Testing & DevOps

(Originally "Part 7" of the build series; updated through the hardening release.)

## Local (no Docker, low RAM)

| Suite | Command (run in folder) | What it proves |
|-------|-------------------------|----------------|
| Backend unit + API tests | `.\mvnw.cmd verify` in `backend/` | 68 tests + JaCoCo gate (≥55% line coverage) |
| Frontend unit tests | `npm test` in `frontend/` | 49 Vitest tests (validation, formatting, API client, typed query hooks, RTL component suite) |
| Frontend build + lint | `npm run build`, `npm run lint` in `frontend/` | Production bundle + ESLint |
| Browser e2e | `npx playwright test` in `frontend/` (stack running) | 8 tests: smoke, a11y, and the full money loop against real Postgres - served on the canonical `:3000` origin (the backend's CORS allow-list rejects others) |
| README screenshots | `npx playwright test --config=playwright.screenshots.config.ts` in `frontend/` | Captures `docs/screenshots/*` from the live seeded product; excluded from the default suite and CI so PNGs only change when regenerated |
| Live stack | `.\start-all.ps1` then `.\seed-demo.ps1` (repo root) | Real Postgres end-to-end, health-gated |

## CI (GitHub Actions, `.github/workflows/ci.yml`)

Six jobs: `backend` (`mvn verify`), `frontend` (`ci → lint → test → build → smoke playwright`),
`docker` (`compose build` + `config --quiet`), `contract` (boots the API against a real
Postgres service, exports the OpenAPI spec, `git diff --exit-code` fails on drift),
`concurrency-postgres` (the parallel-transfer atomicity proof against a real
PostgreSQL service - the test profile's H2 URL is overridden with system properties),
and `banking-e2e` (full stack + seeded users + the whole Playwright suite in a real browser).

## Production-like stack (CI / servers with Docker)

```bash
docker compose up --build
# frontend :3000 -> backend :8080 -> db :5432 (health-gated startup)
```

Images are multi-stage and run as non-root. Secrets via env:
`PG_PASSWORD`, `JWT_SECRET`, `APP_ADMIN_PASSWORD`.

## Deliberate decision: no Testcontainers locally

Testcontainers needs a Docker daemon, which this dev machine skips for RAM reasons.
Instead, Flyway migrations run against H2 in PostgreSQL-compatibility mode in tests,
and every migration is additionally booted against real PostgreSQL via `start-all.ps1`
before merging a part. If CI ever gains a Postgres service, add a
`@DataJpaTest`-with-Testcontainers class reusing `V1__*.sql` - the migrations are already portable.

## Performance notes (measured 2026-09-02, local PG 16)

- Indexes: `idx_accounts_user`, `idx_tx_from`, `idx_tx_to`, `idx_tx_flagged`, beneficiary/audit/card indexes (see V1/V4/V5/V7).
- The paged history query is a native `UNION ALL` of the from/to halves (`TransactionRepository.historyPage`), ordered `created_at DESC, id DESC` with a limit, over `idx_tx_from` / `idx_tx_to`; a composite `(account_id, created_at)` index is the next step if volume grows.
- N+1 audit: every multi-row read resolves counterpart IBANs with one batched `findAllById` (`ibanMap`); no per-row queries in hot paths.
- `GET .../summary` is Caffeine-cached (5 min, 2000 entries) and evicted on every money mutation incl. the interest job.
- Responses carry `X-Request-Id` (minted or propagated) and logs embed it via the `traceId` MDC slot.
