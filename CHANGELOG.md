# Changelog

All notable changes to Northbank are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [SemVer](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-09-03

The ten-part build plus the enterprise-hardening series, complete.

### Parts 1-10 (initial build)

- **Accounts & ledger** - CHECKING / SAVINGS / LOAN accounts, simulated deposit
  rail, atomic transfers with pessimistic locking acquired in stable ID order,
  idempotency keys on `POST /transfers` (replay returns the original row)
- **Interest engine** - monthly scheduled accrual (savings earn, loans charge
  while negative), idempotent per account per month, admin-triggerable
- **Virtual cards** - Luhn-valid issuance, show-once PAN, freeze/unfreeze,
  PAN stored only as hash + last4
- **Notifications & audit** - in-app center with unread badge; every
  register/deposit/transfer/freeze/review writes an `audit_logs` row with
  amount/IBAN metadata
- **Ops console** - user search, account freeze, flagged-transfer review
  queue, daily totals, audit viewer
- **Statements** - CSV + PDF with date ranges, ownership-enforced

### Hardening series (Phases A-H)

- **Repairs** - compose DB seeds extensions only (Flyway owns the schema),
  RFC-7807 on every error path (malformed JSON, bad UUIDs, unknown routes),
  crash-proof API client with typed `ApiError`, deterministic account
  ordering, actuator matchers narrowed, trace header in CORS
- **Domain** - `transactions.kind` + named CHECK constraints (V8), enums for
  role/account/card/tx states, mod-97 IBAN utility with collision retry, one
  DTO mapping layer, audit metadata carries amounts and IBANs
- **Auth** - rotating refresh tokens with reuse detection (family burn),
  TOTP two-factor with QR setup and purpose-bound login challenge,
  `X-Forwarded-For` honored only behind the `TRUST_PROXY_HEADERS` flag,
  deposit caps, hardened refresh cookie flags
- **API platform** - OpenAPI surface with CI contract-drift check, generated
  TypeScript types replace hand-written DTOs, central route constants,
  graceful shutdown, prod JSON logs with trace IDs
- **Data & performance** - UNION ALL history query over composite indexes,
  Hibernate query-count contract on the hot read, cache-eviction proof,
  notification retention purge
- **UI & delivery (this release)** - a11y pass (table scopes, modal focus
  discipline, readable chart months), parallel data loading on
  dashboard/admin/detail, UNDER REVIEW transfer state, password visibility
  toggle, public landing stats, RTL component suite, authenticated
  full-stack Playwright run in CI, Dependabot, demo scripts that wait for
  real backend health
- **Data layer & design (final pass)** - TanStack Query replaces hand-rolled
  fetch effects (typed hooks, cache invalidation on mutations, no refetch
  storms between routes); logout revokes the refresh family server-side, not
  just the cookie; "private-bank ink" design system - bespoke ink/brass
  palette, self-hosted serif display type, statement-style tables, brass
  focus rings - replaces the default dashboard look; the demo seed funds
  accounts and produces a flagged $12,500 wire so the ops review queue is
  never empty; concurrency proof (`TransferConcurrencyIT`) runs in CI against
  a real PostgreSQL service and locally via documented datasource overrides;
  README screenshots are captured from the live product by a dedicated
  Playwright spec kept out of CI

### Known limitations at 1.0.0

- Rate limiting is in-memory (single-instance scope); Redis behind an LB is
  the documented next step
- Tests run on H2 in PostgreSQL mode; the concurrency proof, the live
  contract check, and the banking e2e each run against real PostgreSQL in CI
- No WebAuthn/passkeys yet
