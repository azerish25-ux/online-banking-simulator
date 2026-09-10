# Remediation record

One record for the whole pass: inspected scope, finding, reproduction, changed
symbols, tests, and remaining uncertainty. Updated per issue, never duplicated.

## Baseline (2026-09-09, master @ 2b41cf7, tree clean, 372 tracked files)

| Check | Command | Result |
|---|---|---|
| Backend | `./mvnw -B verify` | exit 0, 214 tests, 0 failures, JaCoCo 0.55 gate met |
| Migration ITs | `./mvnw -B test -Dtest=TransactionKindMigrationIT,KindAuditProvenanceMigrationIT` | exit 0, 2 + 2 tests. Neither class appears in the `verify` output: surefire's default naming does not pick them up. |
| Frontend | `npm ci`, `npm run lint`, `npm run check:tokens`, `npm test`, `npm run build` | all exit 0, 19 files / 154 tests |
| PostgreSQL locally | docker | BLOCKED: no docker daemon on this machine. PostgreSQL-dependent classes run in CI (job-scoped services) and any new concurrency coverage is added there. |
| Browser suite | Playwright | backend-dependent specs need a live stack; run decision recorded under the verification section below. |

The remediation brief derives from static inspection of snapshot 2b41cf7. This
checkout is that snapshot byte-for-byte, so every finding below was reproduced
against the exact audited code before editing.

## Issues

Status after this pass, per issue number from the brief:

| # | Finding | Reproduced | Fixed | Tests |
|---|---|---|---|---|
| 2 | Loan reversals misclassify interest as principal | yes | yes | ReversalWorkflowTest |
| 3 | Auth factor changes issue credentials on stale state | yes (barrier test) | yes | TotpReplacementTest, RefreshRotationConcurrencyTest, RefreshFamilyLifecycleTest, AuthTransitionAtomicityTest |
| 4 | Malformed 2xx body erases the idempotency key | yes | yes | guards/queries vitest suites, e2e commit-then-tamper |
| 5 | Frozen account hides an already recorded deposit | yes | yes | DepositOperationTest, MoneyFlowTest |
| 6 | Operation identity omits kind + originating account | yes | yes | IdempotencyScopingTest, OperationLookupScopeTest |
| 7 | Interest loops step 86400s across DST; scheduler zone omitted | yes | yes | InterestPolicyTest |
| 8 | KindAuditProvenanceMigrationIT unreachable by default discovery | yes (0 mentions in verify output) | yes | failsafe execution bound to verify |
| 9 | Idempotency keys disclosed on history rows (found during this pass) | yes | yes | ReceiptLookupTest |

Details per issue follow.

## Issue 2: transfer reversal through a LOAN leg

Path: `backend/src/main/java/com/bank/platform/ledger/ReversalService.java`
(`reverseTransfer`), `LedgerMovementService.move` / `applyLoanCredit`.

Inspected scope: the whole reversal workflow, the movement core, the loan
repayment allocation, `ReversalWorkflowTest`, V29 migration, the operator
console reverse action.

Finding (reproduced numerically before editing): `reverseTransfer` calls
`movement.move(payee, payer, amount)`. When the original transfer credited a
LOAN, the credit was applied interest-first; the reversal's new movement
debits the loan as a DRAW, which adds the amount back to principal. Loan
principal 100.0000 / balance -110.0000 (10 interest), transfer 10 in
(principal stays 100.0000, balance -100.0000), reverse: principal becomes
110.0000. Interest is reclassified as principal and later accrual charges
interest on it.

Fix: a transfer reversal that touches either LOAN leg is refused before any
mutation, with an explicit unsupported-operation message. Ordinary checking
transfers and non-loan deposits reverse as before. No compensating transfer,
no guessed component reconstruction: that would need immutable per-component
allocation evidence, which the journal does not carry yet.

Remaining uncertainty: none for the refusal; component-exact loan reversal
remains out of scope and is documented as unsupported.

## Issue 7: business-calendar interest

Path: `backend/src/main/java/com/bank/platform/ledger/InterestService.java`
(`accrueSavings`, `accrueLoan`, `accrueMonthly`).

Finding: both accrual loops advance an `Instant` by 86,400 seconds although
the business zone is configurable. In `America/Halifax`, November 2026
produces 31 loop iterations for 30 calendar days and the March loop ends at
April 1 local. Default UTC hides the defect. The savings postings query has
no upper window bound, and the scheduler omits an explicit zone.

Fix: both loops iterate business `LocalDate` values, each boundary derived
with `atStartOfDay(businessZone).toInstant()`, month end exclusive, one
interval per eligible date. The savings opening balance still comes from
today's balance minus ALL later journal postings (unbounded), while the
day loop consumes a separately bounded in-window postings list. The scheduler
cron runs in the business zone.

## Issue 5 (backend): recorded deposit before rules for a new one

Path: `backend/src/main/java/com/bank/platform/ledger/MoneyService.deposit`.

Finding: `requireActive(account)` runs before the replay lookup. A committed
deposit whose response was lost, followed by a freeze, makes the identical
retry answer 400; the frontend then erases the recovery identity and reports
a rejection although the money moved.

Fix: ownership lock, then replay check (canonical hash comparison preserved),
then the active-status rule and the deposit cap. Frozen still blocks new
funding; it no longer hides an already recorded operation from its owner.

## Issue 6 (backend): operation identity

Path: `TransactionRepository.findOperationByKeyAndAccount`,
`MoneyService.operationStatus`, `TransferController`,
`TransferDtos.OperationListItem`.

Finding: the account-scoped lookup matches both key namespaces (transfer
originating from A and deposit funding A) and returns `Optional`, so two
legitimate rows for one key throw an unhandled multiple-result exception. The
recovery list item carries no originating account id, so the client
reconciles by key alone.

Fix: kind discriminator on the lookup (query parameter on
`GET /v1/operations`); missing scope with multiple matches keeps the
controlled ambiguity response. `OperationListItem` gains
`originatingAccountId`. One contract regen covers this and issue 4's fields.

## Issue 3: authentication transitions

Path: `AuthService`, `RefreshService`, `AuthController`, `User`,
`UserRepository`, `TotpEnrollmentRepository`, `LoginChallenge*`.

Finding (barrier-reproduced in tests): factor changes bump `securityVersion`
and revoke refresh rows, but the controller issues the credential pair in a
separate transaction from a possibly stale user snapshot. A password-only
login that pauses between proof and issuance mints a refresh credential after
an MFA activation commits; rotation then mints a current-version access token
without the new factor. `RefreshService.issue` stamps no authentication
epoch. Enrollments are selected then consumed non-atomically. `User` has no
optimistic-lock version. Pending login challenges survive factor changes.

Fix: one authoritative transaction boundary per transition (proof, state
change, credential issuance). Refresh credentials bind to the security epoch
(`V30` backfills the column; issuance locks the user row and refuses when the
proven version no longer matches; rotation burns the family on mismatch).
`User` gains `@Version`. Enrollment consumption is an atomic conditional
update and a promotion supersedes other usable pending enrollments. Factor
changes invalidate pending challenges. Lock order: user row, then
challenge/enrollment/refresh rows.

## Issue 4 + frontend halves of 5 and 6

Path: `frontend/lib/api.ts`, `guards.ts`, `queries.ts`, `pending-op.ts`,
`money-failure.ts`.

Findings: `api()` returns unparseable JSON as the typed value; deposit and
transfer success callbacks ignore the body, so a malformed 2xx erases the
idempotency key; `finish(data.status, data)` runs with an undefined status on
`{}` and clears recovery identity; reconciliation and conflict lookups are
key-only; stored record ids omit the account; replay 4xx was classified as
definitive.

Fix: endpoint-specific zod receipt schemas validated inside the mutation
promise; a malformed success is an unknown outcome (record kept, key kept,
truthful copy); `finish` requires a validated status and a receipt matching
the dispatched identity; lookups and reconciliation carry kind + originating
account; the pending-op store keys on `user | kind | account | key` and
validates records on read; replay 4xx keeps the record as unknown; the store
moves to localStorage with strictly per-user buckets so identity survives
reauthentication without crossing users.## Issue 9: idempotency keys disclosed on history rows (found during this pass)

Path: `TransactionMapper.toResponse`, `TransferController.history` versus the
receipt endpoints.

Finding: every customer surface built its `TransactionResponse` through one
mapping, so the counterparty's `/v1/transactions` history carried the
originator's idempotency key — a client-generated dispatch token that the
originator's recovery flow replays (issue 4 made that string replay-capable
on purpose). It is a credential of the operation, not display metadata.
Found while reconciling the regenerated contract for issues 4 and 6: the
field sat on the shared row shape with no surface distinction.

Fix: the mapper splits the faces. `toResponse` (history, feeds) nulls the
key; `toReceiptResponse` (by-id and by-key lookup of the caller's own
dispatch) carries it; `toAdminResponse` and `toOperationListItem` keep their
existing rules (admin surfaces never show a key; the recovery list carries
the caller's own keys). The shared DTO keeps the field nullable — the
committed contract already declared `type: [string, null]`, so no contract
regen was needed, and the frontend accepts exactly this shape (`transactionSchema`
is passthrough; the receipt schemas pin `.min(1)`).

Remaining uncertainty: none. Receipt faces keep self-identification; no
history face carries any key.

## Verification (recorded at the end of the pass, 2026-09-10)

| Check | Command | Result |
|---|---|---|
| Backend full gate | `./mvnw -B verify` | exit 0. Surefire 231 tests, 0 failures; Failsafe 4 tests, 0 failures (both migration ITs now inside `verify` via the pom gate); JaCoCo coverage gate met. |
| Backend targeted | `./mvnw -B test -Dtest=ReceiptLookupTest` | exit 0, 4 tests (includes the new history-key non-disclosure test). |
| Frontend unit | `npm test` | exit 0, 19 files / 167 tests. |
| Frontend lint | `npm run lint` | exit 0, 0 errors, 1 warning (pre-existing unused test variable in `unresolved.test.tsx`, committed before this pass). |
| Design tokens | `npm run check:tokens` | exit 0, 62 files scanned. |
| Frontend build | `npm run build` | exit 0. |
| PostgreSQL locally | docker | BLOCKED: no docker daemon on this machine. PostgreSQL-dependent ITs run in CI with job-scoped services. |
| Browser suite | Playwright | NOT RUN locally: full-stack specs need a live stack; verified in CI. |

Local PostgreSQL and full-stack browser coverage that could not run locally
is marked as such and verified in CI. FAIL: none; NOT RUN / BLOCKED: the two
rows above.
