# Architecture

## Style
Modular monolith (backend) + server-rendered frontend. Monorepo for portfolio simplicity; clear module boundaries so it can split into microservices later.

```
Browser → Next.js (:3000) → /api/v1/* → Spring Boot (:8080) → PostgreSQL (:5432)
```

## Backend modules
```
com.bank.platform
  config/       # Security, CORS, Jackson, Audit
  auth/         # Users, JWT, register/login
  accounts/     # Account aggregate
  ledger/       # Transactions, transfers
  common/       # ApiError, exceptions, paging
  health/       # HealthController
```

## Frontend routes (target)
```
/              landing + system status
/login, /register
/dashboard     balances + recent activity
/transfers     send money
/admin/*       ops panel
```

## Key decisions (ADR-lite)
1. **Money:** NUMERIC(19,4) + BigDecimal; never float/double. JSON uses string e.g. "100.00".
2. **Transfers:** single @Transactional service, pessimistic lock on accounts ordered by ID (deadlock-safe), idempotency-key header.
3. **Auth:** JWT access (15m) + refresh (7d, httpOnly cookie). Passwords BCrypt(12).
4. **Migrations:** Flyway, versioned SQL in backend/src/main/resources/db/migration.
5. **API errors:** RFC-7807 style { type, title, status, detail, instance, traceId }.
6. **Local DB:** H2 in-memory for tests; Postgres via docker-compose for the full stack.
7. **Posting time:** a `transactions` row records *request* time (`created_at`) and *posting* time (`posted_at`). Only POSTED rows carry `posted_at` (CHECK-enforced; HELD/CANCELLED are intents and stay NULL). Below-threshold transfers/deposits post at submission; review-threshold transfers post when the operator approves them: possibly in a later month. Statements, monthly summaries, daily totals and public stats bucket/cut on `posted_at`; all instants come from one injected business `Clock`.
8. **Operation identity:** every user-submitted money mutation (deposit AND transfer) requires an `Idempotency-Key`, namespaced per originator (deposit: funded account, `from_account_id IS NULL`; transfer: sender account) and fingerprinted with a canonical SHA-256 `request_hash` (kind, source, destination, scaled amount, currency, normalized memo). Same key + same hash = replay returning the original result; same key + different hash = **409 conflict**: never a silent replay of older money. `GET /api/v1/operations?key=` lets the caller resolve their own operation by key.
9. **Reconciled journal:** the ledger splits instruction rows (`transactions`) from immutable posted accounting (`journal_entries` + `journal_lines`, append-only: PG BEFORE UPDATE/DELETE triggers reject even the app role). Every posted operation books one balanced entry (≥2 non-zero postings netting to zero) whose customer-account line mirrors exactly what moved and whose other side is a named counteraccount (`SIMULATOR_FUNDING`/`INTEREST`/`MIGRATION_OPENING`); `(kind, operation_ref)` is unique and the journal write shares the operation's transaction, so money can never move without a balancing record. HELD/CANCELLED never journal. Corrections are new linked entries (`reverses_entry_id`). V22 cutover gave every pre-journal account one labelled `OPENING_BALANCE` entry; balances are projections that reconcile to their journal lines (`GET /api/v1/admin/reconciliation` reports drift and never auto-repairs).
10. **Interest:** deterministic, resumable, per-account accruals: one row per `(account, period)` inserted first in its own transaction (DB unique arbitrates scheduler vs admin overlap; a dead batch resumes). Savings earn actual/365 daily interest on each day's closing balance, derived from journal postings (no invented pre-history). Loans pay simple monthly interest on tracked `accounts.principal` only: never on the balance (no compounding), never capped/forgiven at the credit limit. Repayments extinguish interest before principal and are capped at the amount owed; a loan balance never goes positive.
11. **Commit-safe notifications:** external email is an OUTBOX intent (`email_outbox`, V25) written in the operation's own transaction: a rollback leaves no mail, a crash after commit cannot lose it. A scheduled worker claims due rows with an atomic PENDING→DELIVERING flip (each row delivered exactly once per claim), retries with bounded backoff, dead-letters redacted one-liners to FAILED, and an operator endpoint lists/requeues. Delivery identity dedupes on a unique `delivery_key`; never-attempted rows are always due (timestamp-rounding safe), retries wait on `next_attempt_at` with a 1ms grace.
12. **History paging:** cursor/keyset over the immutable DB `seq`, never OFFSET: a page boundary is the last row's `seq`, read raw from the table, and a feed is documented as live (refresh restarts at the newest page).

## Non-goals
No real bank integration, no real money, no external providers.
