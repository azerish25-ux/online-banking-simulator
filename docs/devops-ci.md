# Testing & DevOps

(Updated through the hardening release
and the 1.3.x hardening campaign. Command results recorded here were executed on this
dev machine (Windows 11, Java 17.0.20, Node 24, local PostgreSQL 16) plus the
GitHub Actions runners in `ci.yml`; the identical commands were NOT assumed to
prove anything on an environment they did not run on.)

## Local (no Docker, low RAM)

| Suite | Command (run in folder) | What it proves |
|-------|-------------------------|----------------|
| Backend unit + API tests | `.\mvnw.cmd verify` in `backend/` | 176 tests + JaCoCo gate (≥55% line coverage) |
| Frontend unit tests | `npm test` (`npx vitest run`) in `frontend/` | 98 Vitest tests (validation, formatting, API client, typed query hooks, RTL component suite) |
| Frontend build + lint | `npm run build`, `npm run lint` in `frontend/` | Production bundle + ESLint |
| Browser e2e | `npx playwright test` in `frontend/` | The run boots its OWN fresh production build on `:3000` and **fails loudly if the port is busy** (`reuseExistingServer: false`: it never silently tests a stale/foreign app). A `global-setup` probes an `E2E_BASE_URL` app (health through the rewrite + nonce CSP) before asserting. Suite = smoke + a11y + the full money loop (incl. the ≥$10k review-threshold hold) + real-browser witnesses (two-tab session + live-page CSP nonce); the silent-refresh specs need a short-TTL backend (`APP_JWT_ACCESS_SECONDS` + `E2E_ACCESS_TTL_SECONDS`, which CI's `banking-e2e` sets). An ephemeral second stack runs via `E2E_BASE_URL` + `APP_CORS_ORIGINS` (2026-09-06 sweep: 19/19) |
| README screenshots | `npx playwright test --config=playwright.screenshots.config.ts` in `frontend/` | Captures `docs/screenshots/*` from the live seeded product; excluded from the default suite and CI so PNGs only change when regenerated |
| Live stack | `.\start-all.ps1` then `.\seed-demo.ps1` (repo root) | Real Postgres end-to-end, health-gated |

## CI (GitHub Actions, `.github/workflows/ci.yml`)

Eight jobs: `backend` (`mvn verify` plus a step running the `TransactionKindMigrationIT`
V7→head reclassification chain: `*IT`-named classes are invisible to Maven's default
surefire/failsafe naming, so it must be requested explicitly),
`frontend` (`ci → lint → test → build → smoke playwright`),
`docker` (`compose build` + `config --quiet`), `contract` (boots the API against a real
Postgres service, exports the OpenAPI spec, and fails on drift via an order-insensitive
canonical comparison of committed vs generated spec),
`concurrency-postgres` (the parallel-transfer atomicity proof against a real
PostgreSQL service: the test profile's H2 URL is overridden with system properties),
`cutover-postgres` (`JournalCutoverIT`: migrate a fresh PG schema to V21, seed
pre-journal rows, migrate to head, and prove the V22 cutover books reconciling
`OPENING_BALANCE` entries that preserve every legacy row),
`journal-postgres` (`JournalReconciliationIT`: append-only triggers reject the app
role, the `(kind, operation_ref)` unique refuses a duplicate journal, corrupted
projections surface in reconciliation and are never auto-repaired), and
`banking-e2e` (full stack + seeded users + the whole Playwright suite in a real browser).

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
`concurrency-postgres`/`cutover-postgres`/`journal-postgres`/`contract`/`banking-e2e`
jobs use a job-scoped Postgres service (each gets a FRESH database, so a migration
IT never sees another test's leftover rows), and local real-PG runs use **disposable
databases** created and dropped around the run (`pf_*` on local PostgreSQL 16: never `bankdb`). The PG runs cover the migration chain V1→head, the reconciled-journal
cutover, journal append-only triggers, reconciliation drift, and the parallel-transfer
exactness/concurrency proofs.

## Performance notes (measured 2026-09-02, local PG 16)

- Indexes: `idx_accounts_user`, `idx_tx_from`, `idx_tx_to`, `idx_tx_flagged`, beneficiary/audit/card indexes (see V1/V4/V5/V7), plus `seq`-based ordering for history.
- History pages with a **keyset cursor over the immutable DB-assigned `seq`** (`GET /transactions?cursor=` → `{items, total, nextCursor}`): no OFFSET, so insertions and equal timestamps between page reads can never duplicate or skip rows. The boundary `seq` is read raw from the table (`rawSeqOf`).
- N+1 audit: every multi-row read resolves counterpart IBANs with one batched `findAllById` (`ibanMap`); no per-row queries in hot paths.
- `GET .../summary` is Caffeine-cached under the supported namespace with typed defaults (5 min, 2000 entries, `LedgerCacheConfig`) and invalidated transaction-aware on every money mutation incl. the interest job and the outbox worker's effects.
- Responses carry `X-Request-Id` (minted or propagated) and logs embed it via the `traceId` MDC slot.
