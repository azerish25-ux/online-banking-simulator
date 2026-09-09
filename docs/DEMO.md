# 2-Minute Demo Script

Audience: interviewer. Stack running via `.\start-all.ps1`, data via `.\seed-demo.ps1`.

## 0:00: Landing (15s)

Open http://localhost:3000. "Online Banking Simulator: Next.js up front, Spring Boot + Postgres behind.
Every money mutation is audited, transfers are atomic and idempotent, and large wires wait for operator approval."

## 0:15: Customer flow (45s)

1. Log in as `alice@bank.local` / `secret123`.
2. **Overview**: total balance, per-account cards, 6-month money-flow chart.
3. **Send money** to Bob's IBAN (shows on dashboard recent list): point out the receipt
   with the transfer id, and that re-sending with the same key returns the same row.
4. **Activity**: filter by date, export CSV, export **PDF** (open it: opening/closing math).

## 1:00: Advanced (30s)

1. Open a **SAVINGS** account from the dashboard, deposit, open the account page,
   **issue a virtual card**: PAN shown once, then masked.
2. Bell icon: transfer/deposit/interest notifications with unread count.

## 1:30: Ops (30s)

1. New tab, log in as `admin@bank.local` / `change-me-admin-123` → **Operations**.
2. Search Alice → **Freeze** her account. Back as Alice: transfer fails with a clear error.
3. Show the **review queue**: the seeded $12,500 wire sits HELD with real Approve/Decline
actions: approve it and both sides get a notification.
4. **Daily totals** + **audit log** filtered to `ACCOUNT_FROZEN`.

## Closer lines

- "Money is NUMERIC/BigDecimal/JSON-string: floats never touch currency."
- "Pessimistic locks are taken in ID order, so opposite-direction transfers can't deadlock."
- "Migrations V1-V7 include a Java migration that retires an unnamed CHECK portably."
- "Residual risks are published in docs/security-review.md: including the ones I chose not to fix yet."
