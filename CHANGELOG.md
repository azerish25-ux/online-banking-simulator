# Changelog

All notable changes to the Online Banking Simulator are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [SemVer](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-09-04

Follow-up audit of the whole repo. The review queue became real, the remaining
ledger races and leak paths closed, and the product shed its invented brand
("Northbank") for a plain description of what it is.

### Review queue is real

- **Transfers at/above the threshold no longer settle on submit.** A HELD row
  records the intent and no money moves until an operator **Approves** (money
  settles under the same ID-ordered locks; the HELD→POSTED flip is atomic so
  two operators can't double-settle) or **Declines** (CANCELLED; nothing ever
  left the sender). The sender is notified on submit, approval and decline.
- Flagged-but-posted rows (large simulated deposits) credit on arrival and
  only need acknowledging to leave the ops queue.
- Customer UI shows honest statuses everywhere (receipt says "submitted for
  review" for a held wire; feeds show HELD / CANCELLED / POSTED), and the ops
  queue offers Approve / Decline per row. The seeded $12,500 wire is now a
  genuine hold awaiting an operator.

### Ledger & money safety

- **Loan interest can no longer break the monthly run**: a loan at its credit
  limit gets its charge clamped so accrual never violates the balance CHECK
  and rolls back interest for every account.
- **Sub-cent amounts are rejected up front** (400, "below the smallest unit")
  instead of 500-ing on the DB `amount > 0` constraint.
- **Account summaries authorize before the cache**: ownership is checked on
  every request, so a cache hit can never leak another user's monthly flows.
- **Idempotency keys are scoped per sender account** (V11 drops the global
  unique), so identical keys from two users can no longer collide.
- Foreign-account reads answer 404 like missing accounts (no existence
  oracle); `RegisterRequest.password` requires `@NotBlank`.
- **Statements are bounded**: windows covering more than `app.statement.max-rows`
  (5,000) are rejected before loading, and CSV cells are sanitized against
  spreadsheet formula injection.

### Auth & deployment

- **Refresh rotation is atomic**: the consumed token is revoked by a
  conditional update, so two concurrent uses of one refresh token can no
  longer both mint sessions (family-burn reuse detection holds under race;
  proven by a concurrency test).
- **Silent refresh actually fires on expiry**: the API answered 403 (Spring's
  default) for missing/invalid/expired credentials, but the browser only
  repairs sessions on 401 - so sessions still died at the first expiry with a
  bare "Forbidden". The security chain now answers 401 RFC-7807 for
  credential failures (403 remains only for authenticated-but-forbidden), the
  refresh cookie is scoped to the proxied path the browser really calls
  (`Path=/backend/v1/auth`), and CI boots the jar with
  `APP_JWT_ACCESS_SECONDS=3` so the whole browser suite runs past real
  expiries (`JwtAccessSecondsOverrideTest` + `session.spec.ts`).
- **Rate-limit topology matches the real shape**: the compose backend
  publishes no host port (proxy-only ingress) and trusts `X-Forwarded-For`,
  so every user gets their own bucket instead of one shared 20/min for the
  whole app.
- Tokens, TOTP labels, PDFs and OpenAPI metadata now read the product name
  from one `Brand` constant.

### UI & hygiene

- The design gallery is a public page (no more login redirect for anonymous
  visitors) and left the customer sidebar; its layout is fixed.
- Freezing a card or an operator freezing an account asks for confirmation.
- Audit metadata is serialized with Jackson instead of hand-rolled JSON
  (values with quotes no longer risk malformed rows); `.editorconfig` +
  `.gitattributes` pin LF + UTF-8 for future writes.
- README, security review, changelog and demo scripts match the code again
  ("Northbank" gone; review-queue claims now true); OpenAPI contract
  regenerated and version-aligned at 1.2.0.

### Code-quality audit pass

- **Money-flow summaries count only settled rows**: a HELD transfer is an
  intent and a CANCELLED one never moved money, so neither may appear as an
  inflow/outflow on the dashboard chart. Regression-tested across hold →
  decline → approve (`SummarySettlementTest`).
- **Errors can be investigated**: the catch-all 500 handler now logs with the
  request trace id (body still stays opaque), malformed `Authorization`
  headers answer 401 instead of escaping the auth filter as a 500
  (`MalformedBearerTokenTest`), and rate-limit 429s share the same RFC-7807
  body shape as every controller.
- **Rate-limit trust model documented + hardened**: degenerate
  `X-Forwarded-For` values can no longer mint fresh buckets; the compose
  comment states the exact proxy requirement (Next stamps XFF from the socket
  peer) and when to revisit it.
- **Money copy is locale-pinned**: `Money.usd` uses US separators even under
  a non-English server locale (`MoneyTest`).
- **Unread badge stays honest**: deposits, account opens and card issues now
  invalidate the unread count, and the badge polls lightly every 30s for
  server-side events (incoming transfers, interest).
- **Transfers form keeps your chosen source account** across refetches - no
  more silent snap back to the first account (`transfers-page.test.tsx`).
- **Schema**: V13 retires the V1 history indexes duplicated by V10 (every
  write paid the same key set twice).
- **Reports & listings scale**: daily totals filter status/window in SQL and
  project only the bucketed columns; every paginated admin/notification
  listing caps the requested page size at 100 (`PageSizeCapTest`).
- **Audit viewer shows the trail**: `audit-logs` now returns each row's JSON
  metadata (amounts, counterparties, IBANs) and the ops UI renders it
  (`AuditMetadataTest`).
- **Statements read like statements**: exports are oldest-first, every PDF
  page repeats its column heading (not just page one), and description
  truncation is code-point safe (`StatementLayoutTest`).
- **One health route**: `/api/v1/health` was unreferenced cruft - `/api/health`
  is canonical; the OpenAPI version now comes from `info.app.version` instead
  of a second hard-coded constant; auth DTOs consolidate in `AuthDtos` and
  `DepositRequest` lives with the account controller.

## [1.1.0] - 2026-09-04

Ten-phase deep-dive audit (code, migrations, CI, UI) fixing the real gaps the
1.0.0 hardening ledger overstated. Every phase shipped with its regression
proof; the security review and the self-audit ledger below it were updated in
the same pass so claims and code never drift again.

### Session integrity

- **Silent refresh now persists the rotated access token** - previously the
  15-minute refresh cycle re-minted a token the browser never saved, so every
  session died at the first expiry. Single-flight rotation, retry-once, and a
  hard `session-expired` redirect when rotation fails.
- Statement CSV/PDF downloads route through the authenticated fetch path
  (they were plain `<a href>` fetches that 401'd after refresh instead of
  retrying), and hard 401s redirect to login instead of sitting on a broken
  page.

### Ledger races

- **Interest accrual is race-proofed**: the job now selects candidate accounts
  with `FOR UPDATE`, so two concurrent runs can't double-accrue (money
  creation). Proven by a parallel-run integration test.
- **Idempotency replays are scoped to their owner** - a replay of another
  user's key could previously surface a foreign transaction. Keys are now
  namespaced by user, source account, and transfer kind; the race window is
  closed by a retry loop that treats the constraint violation as a signal
  instead of poisoning the transaction.

### Auth & ops hardening

- **Deployment env guard**: dev-only credentials (JWT/admin/DB) now warn at
  boot, and `app.deployment-env=production` makes the app refuse to start
  until real secrets are set.
- Rate-limit buckets evict on expiry (no unbounded map), JWT tokens carry
  `iss`/`aud` claims verified at parse, passwords are capped at BCrypt's
  72-byte limit, and the refresh-token purge keeps revoked-but-unexpired rows
  so reuse detection keeps working.
- Every TOTP action (setup/enable/disable/verify) writes an audit row;
  disabling revokes the refresh family like logout does.

### Money & UI correctness

- Transfer idempotency keys are cleared when the transfer intent changes and
  only reused for genuine retries; new transfers mint a fresh key.
- Dashboard totals sum with `BigInt` cents (no float drift) and the recent
  activity table columns align with their header; the Activity account
  switcher now actually refetches the selected account's history.
- Deposits are validated up front (amount required, positive, under the cap)
  instead of depending on a 400 from the service; flagged copy says
  "operator review" instead of over-promising.

### Admin, notifications, a11y, 2FA

- Ops console: paginated users/transactions/audit log with a working audit
  action filter; the review queue and transfer list show human-readable kind
  labels (DEPOSIT/TRANSFER/INTEREST/LOAN_PAYMENT).
- Notifications: bulk `read-all` endpoint + working count refetch after
  mark-read.
- Modals trap focus and announce themselves via `aria-labelledby`.
- **TOTP is complete end to end**: `totpEnabled` on `/me`, a Security page to
  set up/enable/disable the authenticator, and a `/login/mfa` challenge screen
  - before this, enabling 2FA made web login impossible.

### Hygiene & truth

- Fully-qualified type names replaced with imports across the backend;
  duplicate iban-map and statement-download helpers deduped; the generated
  contract was regenerated and the docs (README, security review, self-audit
  ledger) now match the shipped code.

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
