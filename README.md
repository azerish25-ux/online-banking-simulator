# Northbank - Enterprise Banking Platform

A full-fledged banking system built as a 10-part series and completed with an
8-phase hardening pass: **Next.js + TypeScript** frontend, **Java 17 + Spring
Boot 3** backend, **PostgreSQL 16** database. Real money-movement semantics -
atomic transfers, idempotency keys, interest accrual, audit trails - not a CRUD
demo wearing a suit.

| | |
|---|---|
| ![Landing page](docs/screenshots/landing.png) | ![Dashboard](docs/screenshots/dashboard.png) |
| ![Transfer receipt](docs/screenshots/transfer-receipt.png) | ![Admin review queue](docs/screenshots/admin-review-queue.png) |

These are captures of the running product, taken by
[frontend/e2e-screenshots/screenshots.spec.ts](frontend/e2e-screenshots/screenshots.spec.ts)
against the exact stack `start-all.ps1` + `seed-demo.ps1` boot - the flagged
wire in the ops queue is a real threshold-triggered hold, not a prop.

## What it does

- **Accounts** - open CHECKING / SAVINGS / LOAN, simulated deposit rail, pessimistic-lock transfers
- **Money movement** - idempotent transfers, beneficiaries address book, paged history, CSV + PDF statements
- **Interest engine** - monthly job (savings earn, loans charged), idempotent per month, admin-triggerable
- **Virtual cards** - Luhn-valid issuance, show-once PAN, freeze/unfreeze (tokenization-lite: hashes + last4)
- **Auth** - JWT access tokens, rotating refresh tokens with reuse detection, TOTP two-factor with QR setup, login rate limiting, BCrypt(12)
- **Notifications** - in-app center + unread badge, email stub wired into every money event
- **Ops console** - user search, freeze/unfreeze, flagged-transfer review queue, daily totals, audit viewer
- **Security posture** - RBAC, security headers, locked CORS, RFC-7807 errors on every path, `X-Request-Id` correlation, documented residual risks

## Architecture

```mermaid
flowchart LR
    Browser --> Next["Next.js :3000<br/>(rewrite proxy /backend/*)"]
    Next --> API["Spring Boot :8080<br/>/api/v1/*"]
    API --> PG[("PostgreSQL :5432<br/>Flyway V1-V10")]
    API --> Cache[("Caffeine<br/>summaries + public stats")]
```

Money is `NUMERIC(19,4)` in Postgres, `BigDecimal` in Java, and **strings** in JSON -
floats never touch currency. Every mutation writes an `audit_logs` row.

## Run it locally (no Docker)

```powershell
.\start-all.ps1     # Postgres (user-mode) + backend :8080 + frontend :3000, waits for real health
.\seed-demo.ps1     # alice/bob users, funded accounts, a flagged $12,500 wire for the review queue
```

Open http://localhost:3000 - register, or log in with the seeded users
(`alice@bank.local` / `bob@bank.local`, password `secret123`).
Operators: `admin@bank.local` / `change-me-admin-123` → **Operations** in the sidebar.

## API cheatsheet

| Method & path | Auth | Description |
|---|---|---|
| `POST /api/v1/auth/register` | open | Register, auto-opens a CHECKING account |
| `POST /api/v1/auth/login` | open (rate-limited) | JWT access token (15 min) + refresh cookie |
| `POST /api/v1/auth/refresh` · `logout` | cookie | Rotate / revoke the refresh family |
| `POST /api/v1/auth/totp/*`, `mfa/verify` | user | TOTP setup/enable/disable, 2FA login challenge |
| `GET /api/v1/auth/me` | user | Current profile |
| `GET /api/v1/accounts` · `POST /api/v1/accounts` | user | List / open (CHECKING, SAVINGS, LOAN) |
| `POST /api/v1/accounts/{id}/deposit` | owner | Simulated deposit rail, capped + flagged |
| `POST /api/v1/transfers` (+`Idempotency-Key`) | owner | Atomic transfer, ≥$10k auto-flagged |
| `GET /api/v1/transactions?accountId=` | owner | Paged history, date filters |
| `GET /api/v1/accounts/{id}/statement.csv` (.pdf) | owner | Dated statements |
| `GET /api/v1/accounts/{id}/summary?months=` | owner | Monthly inflow/outflow (cached) |
| `GET/POST /api/v1/beneficiaries` | user | Address book (mod-97 validated IBANs) |
| `*/api/v1/accounts/{id}/cards*` | owner | Issue (show-once PAN), list masked, freeze |
| `GET /api/v1/notifications*` | user | Center, unread-count, mark-read |
| `GET /api/public/stats` | open | Landing hero: users, transfers, volume (cached) |
| `GET /api/v1/admin/users|transactions|audit-logs` | ADMIN | Ops search + filtered review queue |
| `POST /api/v1/admin/accounts/{id}/freeze|unfreeze` | ADMIN | Blocks transfers + deposits |
| `POST /api/v1/admin/transactions/{id}/review` | ADMIN | Clears the flag queue |
| `POST /api/v1/admin/interest/run` | ADMIN | Trigger the monthly job on demand |
| `GET /api/v1/admin/reports/daily-totals` | ADMIN | Per-day volumes |

Errors follow RFC-7807 (`type/title/status/detail`), and every response carries
`X-Request-Id` for log correlation. The contract lives at `frontend/openapi.json`
(CI fails if code and spec drift apart) and drives the generated TS types.

## Verify it

```powershell
Set-Location backend; .\mvnw.cmd verify     # 32 tests + JaCoCo gate (H2 in PG mode)
# The concurrency proof against real PostgreSQL (CI's concurrency-postgres job
# runs the identical recipe against its Postgres service):
.\mvnw.cmd test "-Dtest=TransferConcurrencyIT" `
  "-Dspring.datasource.url=jdbc:postgresql://localhost:5432/bankdb" `
  "-Dspring.datasource.username=bankapp" `
  "-Dspring.datasource.password=bankapp_secret_change_me" `
  "-Dspring.datasource.driver-class-name=org.postgresql.Driver"
Set-Location ..\frontend
npm run lint
npm test                                    # 38 tests: lib units + RTL component suite
npx playwright test                         # 8 e2e: smoke + a11y + full money loop (needs the stack running)
npm run build
# README screenshots (requires the seeded stack; kept out of CI by design):
npx playwright test --config=playwright.screenshots.config.ts
```

CI (`.github/workflows/ci.yml`) runs six jobs: backend verify → frontend
lint/test/build + smoke e2e → docker compose build → OpenAPI contract drift
against a Postgres service → **concurrency-postgres** (the 24-transfer proof
against a real PostgreSQL service) → **banking-e2e**: boots the real backend +
PostgreSQL and drives register → deposit → transfer → receipt through a real
browser. Dependabot watches npm, maven, docker, and the actions themselves.

Production-like stack (CI / servers): `docker compose up --build` → :3000 → :8080 → :5432.

## How it was built

Ten parts, each independently runnable and verified live against real Postgres -
see [docs/roadmap-10-parts.md](docs/roadmap-10-parts.md). Supporting docs:
[architecture](docs/architecture.md), [security review](docs/security-review.md),
[testing & CI](docs/devops-ci.md), [2-minute demo](docs/DEMO.md).

## Resume bullets

- Built an enterprise-style banking monolith (Next.js 14 + Spring Boot 3 + PostgreSQL 16)
  with atomic, idempotent money movement and full audit trails
- Designed a 10-migration Flyway schema incl. a Java migration that retires an unnamed
  CHECK constraint portably across PostgreSQL and H2
- Hardened auth with rotating refresh tokens, TOTP 2FA, JWT, RBAC, rate limiting, and
  security headers; documented residual risks in a published security review
- Shipped CI with a contract-drift gate (OpenAPI vs code), an authenticated full-stack
  Playwright job against real PostgreSQL, and JaCoCo coverage gates
- Proved ledger integrity under load: a 24-way parallel-transfer test on real
  PostgreSQL conserves every cent, with idempotent replays posting exactly once
- Designed a bespoke "private-bank ink" interface (custom ink/brass palette,
  serif display type, statement-style tables) instead of a stock dashboard theme
