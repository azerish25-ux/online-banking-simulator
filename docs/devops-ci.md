# Testing & DevOps - Part 7

## Local (no Docker, low RAM)

| Suite | Command (run in folder) | What it proves |
|-------|-------------------------|----------------|
| Backend unit + API tests | `.\mvnw.cmd verify` in `backend/` | 8 tests + JaCoCo gate (≥55% line coverage) |
| Frontend unit tests | `npm test` in `frontend/` | 17 Vitest tests (validation, formatting, API client) |
| Frontend build + lint | `npm run build`, `npm run lint` in `frontend/` | Production bundle + ESLint |
| Browser smoke | `npx playwright test` in `frontend/` | Landing, form validation, auth redirect, gallery (Chromium headless) |
| Live stack | `.\start-all.ps1` then `.\seed-demo.ps1` (repo root) | Real Postgres end-to-end |

## CI (GitHub Actions, `.github/workflows/ci.yml`)

Three jobs: `backend` (`mvn verify`), `frontend` (`ci → lint → test → build → playwright`),
then `docker` (`compose build` + `config --quiet`) once both are green.

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
- `EXPLAIN` on the history query (`from = X OR to = X ORDER BY created_at DESC LIMIT 20`) shows a **seq scan**: the OR across two single-column indexes is not index-friendly. Fine at demo scale; if volume grows, rewrite as `UNION ALL` of two halves over a composite `(account_id, created_at)` access path.
- N+1 audit: every multi-row read resolves counterpart IBANs with one batched `findAllById` (`ibanMap`); no per-row queries in hot paths.
- `GET .../summary` is Caffeine-cached (5 min, 2000 entries) and evicted on every money mutation incl. the interest job.
- Responses carry `X-Request-Id` (minted or propagated) and logs embed it via the `traceId` MDC slot.
