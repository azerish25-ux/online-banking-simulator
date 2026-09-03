# Architecture - Part 1

## Style
Modular monolith (backend) + server-rendered frontend. Monorepo for portfolio simplicity; clear module boundaries so it can split into microservices later.

```
Browser → Next.js (:3000) → /api/v1/* → Spring Boot (:8080) → PostgreSQL (:5432)
```

## Backend modules (target, grown over parts)
```
com.bank.platform
  config/       # Security, CORS, Jackson, Audit
  auth/         # Users, JWT, register/login  (Part 2)
  accounts/     # Account aggregate           (Part 3)
  ledger/       # Transactions, transfers     (Part 3)
  common/       # ApiError, exceptions, paging
  health/       # HealthController (Part 1)
```

## Frontend routes (target)
```
/              landing + system status
/login, /register
/dashboard     balances + recent activity (Part 3/5)
/transfers     send money (Part 3/5)
/admin/*       ops panel (Part 9)
```

## Key decisions (ADR-lite)
1. **Money:** NUMERIC(19,4) + BigDecimal; never float/double. JSON uses string e.g. "100.00".
2. **Transfers:** single @Transactional service, pessimistic lock on accounts ordered by ID (deadlock-safe), idempotency-key header.
3. **Auth:** JWT access (15m) + refresh (7d, httpOnly cookie). Passwords BCrypt(12).
4. **Migrations:** Flyway, versioned SQL in backend/src/main/resources/db/migration.
5. **API errors:** RFC-7807 style { type, title, status, detail, instance, traceId }.
6. **Local DB:** H2 in-memory for Part 1 zero-setup; Postgres via docker-compose from Part 2.

## Non-goals for Part 1
No real auth, no real ledger, no Tailwind (added Part 4 to keep Part 1 install fast).
