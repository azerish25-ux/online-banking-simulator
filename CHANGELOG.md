# Changelog

All notable changes to the Online Banking Simulator are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [SemVer](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Full local Playwright sweep (F22/F23 witnesses + five real fixes)

- The whole browser suite now runs against an ephemeral stack (second backend
  on a free port against a disposable PostgreSQL DB, second frontend on a free
  port whose `BACKEND_URL` is baked at build time, `E2E_BASE_URL` env hook in
  `playwright.config.ts`): **19/19 passed** on the real app - banking journey,
  operator console, TOTP round trip, statement exports, WCAG 2.2 contrast, and
  the two deferred real-tab witnesses.
- F22 witness: `two-tab.spec.ts` - two real tabs share one session, survive a
  full reload past access-token expiry via the silent refresh, and logging out
  in one tab evicts the peer through the auth BroadcastChannel.
- F23 witness: `headers.spec.ts` - live pages carry the full header set, the
  CSP's per-request nonce is the one applied to the document's own scripts,
  production `script-src` has no unsafe-eval/unsafe-inline, and static assets
  carry the header set without a nonce CSP.
- Fixes the sweep surfaced: `Field` now labels a control that sits beside a
  sibling hint (multi-child JSX is an array; the open-account select was
  unlabelled); dynamic client pages unwrap Next 15+/16 async `params` with
  `React.use()` (`/accounts/[id]` and the new `/transfers/receipt/[id]` crashed
  at render); the TOTP setup pane renders again after `/totp/setup`; and
  `/totp/enable` + `/totp/disable` now reissue a credential pair under the new
  security version (promotion revokes every old session - without reissuing,
  the very next call after enabling 2FA died and the app evicted to login),
  with the client adopting an `accessToken` from any successful response body.
- e2e specs brought back in line with the shipped contract: the a11y seed
  sends the mandatory `Idempotency-Key` on deposits, operator.spec drives the
  review step and Decline confirmation, banking.spec disambiguates its POSTED
  badge assertion.

Leftover close-out - receipts, states, and operator queue language.

### Durable receipts (F11)

- Transfers are now a two-step flow: the first submit validates and freezes a
  REVIEW of the exact source/destination/amount/memo; editing returns to the
  draft; Confirm & send submits exactly the reviewed payload.
- New authorized lookup `GET /api/v1/transfers/{id}` (both legs of an
  operation may read it; unrelated parties and unknown ids get the same 404)
  backs a durable bookmarkable receipt route `/transfers/receipt/{id}` that
  always shows the current authoritative status and posting time.
- Operator queue: decision copy comes from the authoritative response, one
  pending decision no longer disables unrelated rows, declining needs a
  confirmation, and held rows show their memo.

### Truthful states & copy (F10/F19)

- Notifications, beneficiaries, and the admin review queue / daily totals /
  audit sections now distinguish loading, empty, and failed-with-retry - a
  failed fetch never reads as an empty list.
- Landing/about copy audited (demo seams already honest; no fabricated
  customers, certifications, or throughput claims found).

### F30 real-PostgreSQL leg

- Challenge single-use consumption, persisted budgets, and account lockout
  are now also verified against real PostgreSQL (disposable database).

Fifth pass - commit-safe notifications and repair of the interaction
primitives before the design polish.

### Commit-safe email outbox (F25)

- Email delivery is now an outbox INTENT committed with the operation that
  produced it: a rolled-back deposit sends nothing, a committed operation's
  mail cannot be lost to a crash. A scheduled worker claims rows after commit
  (atomic PENDING→DELIVERING flip; concurrent workers deliver each row exactly
  once), retries with bounded backoff, dead-letters redacted one-line errors,
  and operators list/requeue dead letters (`GET/POST /api/v1/admin/email-outbox`).
  Delivery dedupes on a unique `delivery_key` (V25, PG-verified).

### Frontend primitives & truthful states (F09/F10/F11/F18/F19 kernels)

- Modal focus lifecycle is keyed on `open` alone (latest-callback ref), so
  typing in a controlled input never yanks focus to the close button; scroll
  locks while open; money dialogs keep Cancel disabled while pending.
- `Field` has an explicit control-ID contract and wires `aria-invalid` plus
  hint/error `aria-describedby`; table cells keep their padding when callers
  add alignment classes.
- Dashboard, activity and account detail distinguish loading / empty /
  failed-with-retry / stale / 404 instead of rendering errors as empty states;
  an unknown transaction status renders explicit UNKNOWN, never assumed POSTED.
- Dashboard totals name their scope and currency; the spending chart draws no
  fake bars for zero months (labelled baseline instead).

### Docs & ops (F27/F29)

- security-review/devops-ci/architecture/README corrected to implementation
  evidence with the tested source state (starting commit + working tree).
- `start-all.ps1` discovers PostgreSQL (no hard-coded install path), waits for
  both services with deadlines, captures logs, and exits non-zero with the
  relevant log tail.

### Read model & API contract (F05/F07/F14/F17/F20/F21/F26/F28)

- **Statements are one immutable snapshot (F05).** A statement (CSV or PDF) is
  composed in a single repeatable-read transaction into one immutable
  snapshot - identity, window, as-of, opening figure, posted rows, closing
  figure, IBAN map - and both renderers are pure over it, so the row list and
  the balance figures can never come from two different moments again. Past
  periods show true window closes, never today's balance.
- **The read-model cache is honest (F07).** Caffeine is configured under the
  supported Spring cache namespace with typed defaults, the summary cache
  keys on normalized month windows + as-of, and invalidation is
  transaction-aware (clears for same-tx visibility and again at completion so
  a concurrent pre-commit repopulation cannot outlive the commit) across
  every money path.
- **History pages by keyset cursor, not OFFSET (F26).** `GET /transactions`
  returns `{items, total, nextCursor}` and takes an opaque `cursor` over the
  immutable DB-assigned `seq`; rows inserted between page reads never
  duplicate or skip, equal timestamps page exactly once, and the feed is
  documented as live history (a cursor is a position - refresh starts at the
  newest page). Frontend `useTransactions`/`queryKeys` and the activity pager
  follow the cursor chain.
- **Public stats classify transfers by kind + posted status (F28).** Loan
  interest charges carry a from side and used to inflate the hero's transfer
  count/volume; only POSTED rows the rail labelled TRANSFER count now.
- **Legacy kinds are reclassified on evidence, never by memo guessing (F21).**
  V24 corrects V8's memo-substring classifications from audit provenance and
  row structure, archives every decision in `transaction_kind_review` (with
  original classification, reason, memo excerpt) for operator review, and
  labels unprovable rows UNCERTAIN - balances, memos and identifiers never
  change. `GET /api/v1/admin/kind-review` surfaces the quarantine.
- **PDF statements render real Unicode (F20).** The PDF embeds a
  licensed broad-coverage font (OFL DejaVu Sans, license bundled) instead of
  substituting missing glyphs: Persian/Arabic, accents and long memos render
  correctly with measured wrapping, page numbers and as-of metadata; RTL runs
  keep a faithful text layer.
- **Amount formatting is exact (F17).** Frontend formatting now does exact
  4-decimal-unit arithmetic (no float cents drift), rejects invalid amounts
  instead of showing `$0.00`, and normalizes negative zero; receipts keep
  sub-cent precision.
- **The API contract names reality (F14).** Login documents both outcomes
  with their real codes (200 session / 202 MFA challenge); every error is one
  typed RFC-7807 `ApiProblem`; response schemas carry explicit `required`
  lists with genuinely nullable fields (`fromIban`/`toIban`/`memo`/`postedAt`)
  and real enums for kind/status/role/type; the public stats route advertises
  no bearer security. The client dropped its blanket `Required<...>` type
  repairs and validates the login branch and error envelope with zod at
  runtime.

### Financial core (F04/F06/F15/F16)

- **Posting time is real.** `transactions` now record request time
  (`created_at`) and posting time (`posted_at`) separately (V19). A
  review-threshold transfer is only REQUESTED at submission and POSTS when an
  operator approves it; below-threshold transfers and deposits post at
  submission. Statements, monthly summaries, daily totals and public stats
  all bucket on `posted_at`, and every money path reads from one injected
  business clock, so a month-boundary approval lands in the month it settled
  in. HELD/CANCELLED rows stay NULL and a DB CHECK keeps POSTED rows honest.
- **Deposits are idempotent like transfers (F06).** Every deposit and every
  transfer requires an `Idempotency-Key`, scoped to its originator and
  fingerprinted with a canonical SHA-256 request hash. Replaying the same
  intent returns the original result; reusing a key for different money is
  now a **409 conflict**, never a silent replay. `GET /api/v1/operations?key=`
  resolves the caller's own operation by key. The deposit dialog keeps one
  key per funding intent - persisted across reloads, reset on intent
  change/logout - so the retry contract holds in the UI too.
- **The ledger has a reconciled journal (F15).** `journal_entries` and
  `journal_lines` are append-only (PostgreSQL triggers refuse UPDATE/DELETE
  even for the application role) and record one balanced entry per posted
  operation - a customer line mirrored by a named counteraccount. Money can
  no longer move without a balancing record, and a duplicate journal for one
  operation is refused by the database. The V22 cutover gave every
  pre-journal account a single labelled `OPENING_BALANCE` entry that
  reproduces its balance, so the whole history reconciles to the journal;
  legacy rows were preserved untouched. Operators can check drift via
  `GET /api/v1/admin/reconciliation` - reported, never auto-repaired.
- **Interest is bounded, resumable, and no longer forgives debt (F16).**
  Accrual runs in per-account transactions guarded by a unique
  `(account, period)` row, so overlapping scheduler/admin runs cannot
  double-post and an interrupted batch resumes. Savings earn actual/365 daily
  interest on each day's closing balance from journal postings; loans pay
  simple monthly interest on tracked principal only - no compounding, and a
  loan at its limit is charged, not forgiven. Repayments extinguish interest
  before principal and are capped at the amount owed.
- **Verified against real PostgreSQL on disposable databases**: journal
  append-only + duplicate rejection + drift reporting, the V21→head cutover,
  and the transfer-concurrency proofs (8/8 ITs green), plus the full H2 gate
  (backend 152 tests, frontend 82 tests) and the OpenAPI contract for the two
  new endpoints.

### Supported dependency alignment (F13)

- **The runtime lines are back inside current support windows.** Next.js 14 →
  **16.3** (Active LTS) with React 18 → **19.2** and the React 19 types;
  Spring Boot 3.2 → **4.1.1** (the open-source-supported line - every 3.x
  branch reached end of OSS support on 2026-06-30); Node 20 → **24 LTS** in
  CI, the Dockerfile, and docs. Java stays 17 (still fully supported by Boot
  4). Each upgrade was chosen from the official support pages at execution
  time, not from the audit's then-current versions.
- **The frontend lint gate moved with the framework.** `next lint` was removed
  in Next 16, so linting runs the ESLint CLI against a flat
  `eslint.config.mjs` (eslint-config-next 16's flat rule sets, ESLint 9 - the
  React plugin's latest does not support ESLint 10 yet), and `next build` no
  longer lints.
- **Boot 4's modular starters and Jackson 3.** `spring-boot-starter-webmvc`,
  `-flyway`, and the `-webmvc-test`/`-security-test` test starters replace the
  monolith starters; the app's JSON engine is Jackson 3 (`tools.jackson`), the
  Boot 4 default, with springdoc 2.5 → 3.1. Tests were ported to the relocated
  `@AutoConfigureMockMvc` (`org.springframework.boot.webmvc.test...`) and the
  generated OpenAPI contract regenerated (the path/schema surface is
  unchanged; springdoc 3 now also reflects bean-validation constraints).
- **Test toolchain to maintained versions**: Vitest 2.1 → 5 with
  `@vitejs/plugin-react` (Vite 8 refuses `jsx: preserve`), jest-dom 7,
  @testing-library/react 16. `npm audit` reports **0 vulnerabilities** on the
  installed tree (the previous Vitest 2 → Vite 5 → esbuild chain carried a
  reachable dev-server advisory).

### Correctness

- **Statement opening/closing balances are now true for any window**, not
  just the default "last 30 days". The PDF previously derived both figures
  from the account's *current* balance minus in-window net, so a statement
  for a past period printed today's balance as its closing. Closing is now
  the balance at the window's end (current minus settled movement strictly
  after it), opening is closing minus the in-window net, and `to` dates are
  inclusive exactly like the history filter they mirror
  (`StatementService`, new `sumSettledMovementAfter` repository query, and a
  `StatementBalanceTest` that pins both past-window and current-window
  figures).
- **The deposit rail lets you choose where the money lands.** It previously
  funded the oldest account silently - always the auto-opened CHECKING - so
  a SAVINGS account could never be funded and a LOAN could not be repaid
  through the headline rail. The dialog now picks the destination account,
  disables frozen ones, and says where the deposit went in the success toast.
- **A transfer to an IBAN that isn't an account here explains itself** -
  same 404, but the detail says the recipient must have an account in the
  simulator instead of echoing the searched value.

### Design system

- **The palette is now enforced, not just claimed.** Semantic tokens
  (`content` ramp, `success`/`danger`/`warning`/`info` surface pairs,
  `outflow`, `scrim`) replace every default Tailwind hue (`slate`/`red`/
  `emerald`/`amber`/`sky`) and every raw hex in views and the flow chart;
  `scripts/check-design-tokens.mjs` fails the build on either
  (`npm run check:tokens`, wired into CI).
- **New `Select` primitive** replaces three ad-hoc select styles; `Pager`
  gains arrows and is used by Activity and Notifications; `maskIban` becomes
  the one masked-IBAN helper (dead `shortId` removed, local duplicates
  deleted); stray formatting and the redundant `caps ... normal-case` pairing
  are gone in favor of a sentence-case `label` utility.
- **Comment hygiene on the ledger**: the stale-snapshot "first-read"
  discipline now lives once (at `LedgerMovementService`); `MoneyService`
  points at it instead of re-explaining it twice.

### Product & UX

- **New public [/about](/about) page** - the demo seam. What is simulated,
  what is real, the safety rails, and residual risks in plain words; linked
  from the landing page, the auth screens, and the sidebar.
- **Customer screens speak bank, not internals.** The landing hero and
  feature columns were rewritten (no more slogan-as-filler or
  "there is a test that races it"); the transfer page no longer explains
  idempotency keys or atomicity to people trying to pay rent - the review
  desk does the talking, and the mechanics live behind the seam.
- **Deposit affordance renamed** "Deposit funds" (the e2e specs follow), so
  a customer action reads like a product action with the simulation noted
  where it matters.
- **A restrained motion language**: modal overlay/dialog entrance, toast
  slide-in with a dismiss button, skeleton pulse gated behind
  `prefers-reduced-motion` via `motion-safe:` variants.
- **Mobile shell rebuilt**: brand + actions on one row, a horizontally
  scrollable nav beneath - no more overflow at 360px, `aria-current` on the
  active item.
- **Micro-truth fixes**: the dashboard hero says "Available balance" for a
  single account instead of "Total across accounts"; `EmptyState` no longer
  nests a Card inside a Card; table headers and form labels share one
  letter-spaced label style.
- The design gallery grew into a style guide - tokens, motion, and the
  two-register voice rule - so future work has a reference to match.

### Playtest fixes

- **Statement exports actually download.** CSV and PDF exports 404'd on the
  real stack: the URL builders already carried the `/backend` proxy prefix
  while `downloadAuthed` prepended it again, so every export hit
  `/backend/backend/...`. The builders now return plain API paths (the proxy
  prefix belongs to the fetch layer), pinned by a unit regression and a new
  e2e test that downloads both files through the running proxy.
- **Amounts are validated where they are typed.** A zero amount previously
  passed client validation and was rejected only by a round-trip toast; a
  4-decimal amount was stored but displayed rounded to cents, so $1.2345
  toasted as "Deposited $1.23" and could strand sub-cent dust. Entry is now
  cents-only and zero is rejected inline on the deposit and transfer forms.
- **Opening a loan no longer dead-ends.** A fresh loan sat at $0.00 with no
  hint that drawing means transferring *from* it. The loan account page now
  explains how to draw (up to the $1,000 line) and how to repay in both the
  drawn and undrawn states, and the open-account option says the same.

### Browser witnesses

- **The operator console is now proven in the browser, not just by backend
  suites** (`e2e/operator.spec.ts`): a fresh customer funds checking in
  sub-threshold installments, holds a $10,000 transfer, an operator approves
  it and later declines a second one, and the spec asserts every surface a
  human would check - the review queue empties per decision, the audit log
  records `TRANSFER_APPROVED`/`TRANSFER_DECLINED` with the moved funds, the
  customer's balances move exactly on approval and never on decline, and the
  feed shows `POSTED` then `CANCELLED`. Queue rows are matched by masked
  sender→recipient tails plus amount, never by position, so parallel specs
  and stale rows cannot flake it.
- **The 2FA round trip is browser-covered end to end** (`e2e/totp.spec.ts`
  + `e2e/totp-code.ts`, a ~40-line RFC 6238 generator): a fresh user enables
  TOTP reading the real secret from the screen, signs out, is challenged at
  the next login before any session exists, completes the challenge, then
  disables 2FA and logs in again unchallenged. The generator was verified
  against the live verifier before the spec was trusted.

### Error consistency

- **A server rejection now renders where the user is looking, not as a
  corner toast behind a scrim.** Dialog-surface mutations keep their
  dialogs open with the offending value still in the field; the rejection
  is drawn next to that field (a deposit over the $100k cap under the
  amount, a wrong authenticator code under the code) or as a dialog-level
  alert inside the dialog (open-account, remove-beneficiary - previously
  silent - card/account freeze confirms). Page-level mutations (transfer
  send, operator review, beneficiary save) keep their corner toasts. All
  of it still flows through the single `useResultToast` owner: it gained
  one additive `onFailure` channel so dialogs render the failure inline
  (`error: false`) while success, copy, and fire-exactly-once semantics
  are untouched; an `InlineAlert` primitive owns the alert look and
  `ConfirmDialog` gained an `error` slot. A banking e2e witness pins the
  over-cap deposit rejection under the amount field with the dialog open
  and no corner toast.

## [1.3.0] - 2026-09-05

Third audit pass: real bugs first (one of them shipped with the working
1.2.0 refactor), then API/session hardening, then the machine-flat edges that
made the product feel generated rather than built.

### Correctness

- **Money can no longer be created or destroyed under concurrent transfers on
  real PostgreSQL** - the deepest bug this audit found, and one the H2 suite
  could not see. `MoneyService.transfer` loaded both accounts *before* the
  ledger's `SELECT ... FOR UPDATE`, and Hibernate does not refresh an entity
  already in the persistence context: under real row-lock contention the lock
  was taken on a stale snapshot whose save then overwrote a newer committed
  balance. 24 parallel opposite transfers on Postgres produced 2030 where
  2000 belonged (caught by `TransferConcurrencyIT` on the Postgres service;
  green on H2 by timing luck). The instant path now resolves both accounts to
  IDs only, so the locking read in `LedgerMovementService#move` is also the
  first read - the row it locks is the row it loads - and `accounts` gained
  an optimistic-lock `version` (V16) so any future unlocked stale write fails
  loudly with 409 instead of silently corrupting a balance.
- **A review-threshold transfer no longer toasts "Transfer posted".** The
  success effect compared a bare `status`, which resolved to the legacy
  `window.status` DOM global instead of the response - so every held wire
  reported itself as settled while its receipt card correctly said HELD.
  Regression-tested in RTL and covered by a new e2e flow that deposits past
  the threshold and sends a real held transfer (`transfers-page.test.tsx`,
  `banking.spec.ts`). ESLint's `no-restricted-globals` now bans bare `status`
  so the class of bug cannot silently compile again.
- **Missing `amount` on transfers/deposits answers 400, not 500** - a body
  without it used to reach `new BigDecimal(null)` and fall into the catch-all.
  `amount` is now `@NotNull` on both payloads (OpenAPI contract regenerated:
  it is `required` in the committed spec).
- **History pagination cannot overflow anymore** - `page=Integer.MAX_VALUE`
  used to wrap the `int` OFFSET negative and 500 in SQL; the page depth is
  now capped and an out-of-range page returns an empty result.
- **Customer history and statements order by real insertion order**: rows
  persisted in one flush share `created_at`, and the random UUID id can't
  break the tie - the DB-assigned `seq` (V14) now does, matching the admin
  queue. Listings and exports can never shuffle same-instant rows.

### Security & session hardening

- **TOTP verification is throttled per account** (`mfa/verify`, `totp/enable`,
  `totp/disable`): a per-account budget of 5 wrong codes per minute answers
  429 `Retry-After` when exhausted - six digits can no longer be brute-forced
  at network speed by a client holding a session or a challenge token
  (`TotpThrottle`, `TotpThrottleTest`).
- **The `bank_token` cookie dies with the JWT it carries**: Max-Age dropped
  from 7 days to the 15-minute access-token lifetime, and `Secure` is set over
  HTTPS. Silent refresh rewrites the cookie on every rotation, so active
  sessions never notice; a session idle past the TTL bounces to login on its
  next navigation (routing-only trade-off, documented in `middleware.ts` and
  the security review).
- **Credit is capped**: a customer could previously open unlimited LOAN
  accounts and mint unbounded $1,000 credit. One open loan per user now -
  service-level check plus a PostgreSQL partial unique index (V15, Java
  migration skipped on H2), and the dashboard disables the Loan option once
  one exists (`AccountLimitsTest`).
- `/actuator/health` no longer exposes DB/disk detail to anonymous callers
  (`show-details: never`); `/api/health` stays the liveness probe.

### Ops & API quality

- The admin **review queue is paginated** - it previously showed a fixed
  first-20 snapshot with no way to reach older items; resolving the last item
  on a page steps back instead of stranding the operator on an empty page.
- One currency policy (`Currencies.normalize`) replaces the duplicate private
  checks in the two money-movement paths; CSV statement rendering moves into
  `StatementService` beside the PDF renderer so both exports share one idea
  of what a statement is; `AuditLog.of(...)` collapses the
  new-then-`setMetadata` boilerplate repeated across six services.

### UI & copy

- Browser tabs name the page you're on ("... · Send money", "... · Operations")
  instead of only the brand; timestamps add the year when a row belongs to a
  different one; the notifications unread dot is labelled for screen readers.
- Formatting tidy-up (mashed imports, stray blank lines) and the dependency
  floors raised: Spring Boot 3.2.5 → 3.2.12 (final 3.2.x), Next 14.2.5 →
  14.2.35.

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
