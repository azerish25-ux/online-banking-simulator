# Testing & DevOps

(Originally "Part 7" of the build series; updated through the hardening release
. Command results recorded here were executed on this
dev machine (Windows 11, Java 17.0.20, Node 24, local PostgreSQL 16) plus the
GitHub Actions runners in `ci.yml`; the identical commands were NOT assumed to
prove anything on an environment they did not run on; exact per-phase test
counts and the tested source state are in the commit history.)

## Local (no Docker, low RAM)

| Suite | Command (run in folder) | What it proves |
|-------|-------------------------|----------------|
| Backend unit + API tests | `.\mvnw.cmd verify` in `backend/` | 171 tests + JaCoCo gate (≥55% line coverage) |
| Frontend unit tests | `npm test` (`npx vitest run`) in `frontend/` | 94 Vitest tests (validation, formatting, API client, typed query hooks, RTL component suite) |
| Frontend build + lint | `npm run build`, `npm run lint` in `frontend/` | Production bundle + ESLint |
| Browser e2e | `npx playwright test` in `frontend/` (stack running) | smoke + a11y + the full money loop (including the ≥$10k review-threshold hold) + the silent-refresh spec, all against real Postgres on the canonical `:3000` origin (the backend's CORS allow-list rejects others) |
| README screenshots | `npx playwright test --config=playwright.screenshots.config.ts` in `frontend/` | Captures `docs/screenshots/*` from the live seeded product; excluded from the default suite and CI so PNGs only change when regenerated |
| Live stack | `.\start-all.ps1` then `.\seed-demo.ps1` (repo root) | Real Postgres end-to-end, health-gated |

## CI (GitHub Actions, `.github/workflows/ci.yml`)

Six jobs: `backend` (`mvn verify`), `frontend` (`ci → lint → test → build → smoke playwright`),
`docker` (`compose build` + `config --quiet`), `contract` (boots the API against a real
Postgres service, exports the OpenAPI spec, and fails on drift via an order-insensitive
canonical comparison of committed vs generated spec),
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

## Real PostgreSQL evidence without Testcontainers

Testcontainers needs a Docker daemon, which this dev machine skips for RAM reasons.
Flyway migrations run against H2 in PostgreSQL-compatibility mode in the fast suites,
and every migration additionally boots against **real PostgreSQL**: CI's
`concurrency-postgres`/`contract`/`banking-e2e` jobs use a Postgres service, and local
real-PG runs use **disposable databases** created and dropped around the run
(`pf_*` on local PostgreSQL 16 - never `bankdb`). The PG runs cover the migration
chain V1→head, the reconciled-journal cutover, journal append-only triggers,
reconciliation drift, and the parallel-transfer exactness/concurrency proofs.

## Performance notes (measured 2026-09-02, local PG 16)

- Indexes: `idx_accounts_user`, `idx_tx_from`, `idx_tx_to`, `idx_tx_flagged`, beneficiary/audit/card indexes (see V1/V4/V5/V7), plus `seq`-based ordering for history.
- History pages with a **keyset cursor over the immutable DB-assigned `seq`** (`GET /transactions?cursor=` → `{items, total, nextCursor}`) - no OFFSET, so insertions and equal timestamps between page reads can never duplicate or skip rows. The boundary `seq` is read raw from the table (`rawSeqOf`).
- N+1 audit: every multi-row read resolves counterpart IBANs with one batched `findAllById` (`ibanMap`); no per-row queries in hot paths.
- `GET .../summary` is Caffeine-cached under the supported namespace with typed defaults (5 min, 2000 entries, `LedgerCacheConfig`) and invalidated transaction-aware on every money mutation incl. the interest job and the outbox worker's effects.
- Responses carry `X-Request-Id` (minted or propagated) and logs embed it via the `traceId` MDC slot.
