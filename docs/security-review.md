# Security Review

(Originally "Part 6"; re-verified through the hardening release - refresh
rotation, TOTP, proxy-header trust flag, deposit caps, RFC-7807 everywhere -
and again through the 1.1.0 deep-dive audit of 2026-09-04 (session-refresh
persistence, idempotency scoping, the interest race, the TOTP web UI, a
fail-fast deployment guard) and the 1.2.0 audits of the same day, which made
review-threshold transfers genuinely hold until an operator approves them,
scoped idempotency keys per sender account, capped statements, closed the
summary-cache authorization gap, re-scoped the refresh cookie to the path the
browser actually calls, made the RFC-7807 error surface complete, and capped
passwords by UTF-8 byte length instead of characters. The 1.3.0 audit
(2026-09-05) throttled TOTP verification per account, tied the access-token
cookie to its JWT's lifetime, capped credit at one open loan, made history
orderings deterministic via the `seq` column, and paginated the review queue.)

Scope: Spring Boot API + Next.js frontend, local single-instance deployment.
Method: code review + automated tests (82 fast tests + JaCoCo gate, concurrency
IT against real Postgres in CI) + live verification on the seeded stack.

## ✅ Passing

| # | Control | How it holds |
|---|---------|--------------|
| 1 | Password storage | BCrypt(12), never logged or returned (no getter on the wire; `UserResponse` excludes hash); the 72-byte BCrypt ceiling is enforced as bytes (`@PasswordBytes`), so multibyte passwords can't silently truncate |
| 2 | AuthN | JWT HS512, 15-min access tokens, `sub`/`role`/`iss`/`aud`/`jti` claims, signature + issuer + audience verified per request |
| 3 | AuthZ | Stateless filter sets `ROLE_*`; `/admin/**` additionally guarded by `@PreAuthorize("hasRole('ADMIN')")` (defense in depth: `AdminService` re-checks the role). Credit is bounded: at most one open LOAN account per user (service check + PostgreSQL partial unique index, V15) |
| 4 | Credential stuffing | Token-bucket rate limit on login/register (20/min/real-IP default, `Retry-After`, isolated test at 5/min). `X-Forwarded-For` is honored only behind `TRUST_PROXY_HEADERS=true`; in the compose deployment the backend publishes no host port, so every request arrives through the Next.js proxy and gets its own bucket |
| 4b | TOTP brute force | Six-digit codes are only 10^6 values, so a per-IP limit alone cannot stop guessing from many addresses or from a held session. `mfa/verify`, `totp/enable` and `totp/disable` share a per-account failure budget (5/min, success resets) that answers 429 `Retry-After` when exhausted (`TotpThrottle`) |
| 5 | Login enumeration | Identical "Invalid email or password" for unknown email vs wrong password; register-duplicate 409 is accepted tradeoff |
| 6 | Money safety | Pessimistic locking (ID-ordered), `NUMERIC(19,4)` + `BigDecimal`, amounts as JSON strings; idempotency keys scoped to owner + source account + kind (a foreign replay returns 404, never another user's row); interest accrual selects candidates `FOR UPDATE` so concurrent runs can't double-accrue |
| 7 | Frozen accounts | `FROZEN` status blocks transfers AND deposits at the service layer; freeze/unfreeze audited with admin actor |
| 8 | Audit trail | Every register/deposit/transfer/freeze writes `audit_logs`; admin-only viewer with action filter |
| 9 | Transport headers (API) | `nosniff`, `DENY` framing, `no-referrer`, locked `Permissions-Policy`, strict CSP (`default-src 'none'`) |
| 10 | CORS | Exact-origin allowlist (default `http://localhost:3000`), only needed methods/headers; browser app uses same-origin proxy anyway |
| 11 | Secrets | JWT secret, admin password, DB creds via env (`JWT_SECRET`, `APP_ADMIN_*`, `PG_*`); defaults warn loudly at boot and `app.deployment-env=production` refuses to start until real secrets are set |
| 12 | Error handling | RFC-7807 on every path: field validation, business failures, constraint violations, malformed JSON/UUIDs, unknown routes, and a catch-all so even an unhandled exception answers a problem+json body - never HTML or a stack trace. Missing/invalid/expired credentials answer **401** (the status the browser's silent refresh keys on) via the security entry point; authenticated-but-forbidden stays **403** - both RFC-7807 |
| 13 | Review queue | Transfers at/above the threshold never settle on submit: a HELD row records the intent and an operator must approve it (money moves under the same ID-ordered locks; the HELD→POSTED flip is atomic so two approvals can't double-settle) or decline it (CANCELLED, no money ever moves). Flagged deposits credit on arrival and only need acknowledging. Replays of a held transfer's idempotency key return the same row |

## ⚠️ Known limitations (documented, not ignored)

1. **Default admin password** (`change-me-admin-123`) if env is unset - boot log warns loudly; production checklists must set `APP_ADMIN_PASSWORD`.
2. **Rate limiter is in-memory per instance** - buckets evict on expiry, which is correct for one instance; Redis is the documented step behind a load balancer.
3. **Refresh rotation + TOTP complete** - 7-day HttpOnly rotating refresh with reuse detection (family burn on replay). The purge job deletes only expired rows - revoked-but-unexpired rows must survive or replay detection breaks. TOTP is end-to-end: setup/QR/secret on the Security page, enable and disable (code-confirmed), and a 5-minute purpose-bound `/login/mfa` challenge - verified live with real codes. Remaining: no WebAuthn/passkeys yet.
4. **No account lockout** - per-IP rate limiting + BCrypt cost + the per-account TOTP budget make online brute force uneconomical; a hard lockout would risk user-enumeration and support load.
5. **Middleware role check is UX-only** - it decodes (not verifies) the JWT for routing; the API re-verifies signature + role on every call.
6. **Idle sessions bounce on next navigation** - the `bank_token` cookie's Max-Age mirrors the 15-minute JWT (a stolen cookie dies with the token it carries), so a session idle past that TTL is redirected to login on its next page load even though the HttpOnly refresh cookie could still repair it. Active sessions rotate silently and never notice; a full BFF (no browser-visible tokens) would remove the trade-off.

## v2 - hardening series (Phase C)

- `X-Forwarded-For` is ignored unless `TRUST_PROXY_HEADERS=true`; deposits capped at `DEPOSIT_MAX` (default 100000) and flagged at the review threshold.
- Actuator matchers narrowed to health/info; CORS allows + exposes `X-Request-Id`.
- Refresh cookie: `HttpOnly; Path=/backend/v1/auth; SameSite=Lax`, `Secure` iff `COOKIE_SECURE=true` - scoped to the proxied path the browser actually calls, so the cookie is sent on refresh requests (previously `Path=/api/v1/auth`, which never matched `/backend/v1/auth/*`, silently killing every session at the first token expiry).
- Access cookie stays readable for edge routing only - blast radius 15 minutes; its Max-Age mirrors the JWT lifetime (it used to linger 7 days), `Secure` is set over HTTPS, and the silent-refresh path rewrites it on every rotation; documented in `middleware.ts`.

## How to re-verify

```powershell
.\mvnw.cmd verify                    # backend: 82 tests + JaCoCo gate (from backend/)
cd ..\frontend; npm run lint; npm test   # 60 unit/component tests
npm run build; npx playwright test   # e2e against the running stack
.\start-all.ps1; .\seed-demo.ps1     # live stack + demo data
# customer: alice@bank.local / secret123 -> Security -> Set up authenticator,
#   enter a code from an authenticator app, log out, log in -> /login/mfa challenge
# operator: admin@bank.local / change-me-admin-123 -> Operations -> review queue:
#   the seeded $12,500 wire sits HELD - Approve settles it, Decline cancels it;
#   freeze Alice -> her transfer fails 400
```
