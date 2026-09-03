# Security Review - Part 6

Scope: Spring Boot API + Next.js frontend, local single-instance deployment.
Method: code review + automated tests (AdminFlowTest, RateLimitTest) + live verification.

## ✅ Passing

| # | Control | How it holds |
|---|---------|--------------|
| 1 | Password storage | BCrypt(12), never logged or returned (no getter on the wire; `UserResponse` excludes hash) |
| 2 | AuthN | JWT HS256, 15-min access tokens, `sub` + `role` claims, signature verified per request |
| 3 | AuthZ | Stateless filter sets `ROLE_*`; `/admin/**` additionally guarded by `@PreAuthorize("hasRole('ADMIN')")` (defense in depth: `AdminService` re-checks the role) |
| 4 | Credential stuffing | Token-bucket rate limit on login/register (20/min/IP default, `Retry-After`, isolated test at 5/min) |
| 5 | Login enumeration | Identical "Invalid email or password" for unknown email vs wrong password; register-duplicate 409 is accepted tradeoff |
| 6 | Money safety | Pessimistic locking (ID-ordered), `NUMERIC(19,4)` + `BigDecimal`, amounts as JSON strings, idempotency keys |
| 7 | Frozen accounts | `FROZEN` status blocks transfers AND deposits at the service layer; freeze/unfreeze audited with admin actor |
| 8 | Audit trail | Every register/deposit/transfer/freeze writes `audit_logs`; admin-only viewer with action filter |
| 9 | Transport headers (API) | `nosniff`, `DENY` framing, `no-referrer`, locked `Permissions-Policy`, strict CSP (`default-src 'none'`) |
| 10 | CORS | Exact-origin allowlist (default `http://localhost:3000`), only needed methods/headers; browser app uses same-origin proxy anyway |
| 11 | Secrets | JWT secret, admin password, DB creds via env (`JWT_SECRET`, `APP_ADMIN_*`, `PG_*`); defaults are dev-only with a loud boot warning |
| 12 | Error handling | RFC-7807 bodies, no stack traces to clients, validation messages field-scoped |

## ⚠️ Known limitations (documented, not ignored)

1. **Default admin password** (`change-me-admin-123`) if env is unset - boot log warns loudly; production checklists must set `APP_ADMIN_PASSWORD`.
2. **Rate limiter is in-memory** - correct for one instance; move to Redis behind a load balancer.
3. **No refresh-token rotation / 2FA yet** - access tokens are short-lived (15m); TOTP is a Part 8+ candidate.
4. **No account lockout** - rate limiting + BCrypt cost make online brute force uneconomical; lockout risks user-enumeration and support load.
5. **Middleware role check is UX-only** - it decodes (not verifies) the JWT for routing; the API re-verifies signature + role on every call.

## How to re-verify

```powershell
.\mvnw.cmd test                      # backend: 8 tests incl. freeze flow + 429 test (from backend/)
npm run build                         # frontend (from frontend/)
.\start-all.ps1; .\seed-demo.ps1      # live stack + demo data
# login as admin@bank.local / change-me-admin-123 -> Operations -> freeze Alice -> her transfer fails 400
```
