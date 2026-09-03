# 10-Part Roadmap - Enterprise Banking Platform

> Goal: portfolio-grade, enterprise-style banking system. Each part is independently runnable and ends with a verifiable checkpoint.
>
> **Status 2026-09-02: all 10 parts complete and verified.** Backend 14 tests + JaCoCo gate, frontend 20 Vitest + 4 Playwright, live Postgres proving runs per part.

## Part 1 - Foundation & Monorepo Scaffold (CURRENT)
- Monorepo layout (frontend / backend / db / docs)
- Next.js + TS app router with landing + health page
- Spring Boot skeleton with GET /api/health + Actuator
- Postgres docker-compose + init.sql (users/accounts/transactions DDL preview)
- VS Code workspace (settings, extensions, launch)
- Docs: architecture, setup, roadmap
- **Checkpoint:** `npm run dev` serves :3000, backend compiles, `docker compose config` valid

## Part 2 - Database Design + Auth
- Final ERD: users, accounts, transactions, audit_logs
- Flyway migrations, JPA entities, repositories
- Spring Security + JWT (access/refresh), password hashing (BCrypt)
- Register/login/me endpoints + validation + global exception handler
- Frontend: login/register pages, auth context, protected routes
- **Checkpoint:** register → login → me works end-to-end against Postgres

## Part 3 - Core Banking Domain (Accounts & Transactions)
- Account creation, balance rules (no negative), transaction ledger (double-entry style)
- Transfer API with @Transactional + pessimistic locking, idempotency keys
- Pagination/filtering for transaction history
- Frontend: dashboard, transfer form, history table
- **Checkpoint:** transfer $100 A→B updates both balances atomically

## Part 4 - Frontend Design System
- Tailwind + shadcn-style tokens, layout shell, nav, theme
- Reusable components: Card, Table, Modal, Forms (React Hook Form + Zod)
- Error boundaries, loading skeletons, toasts
- **Checkpoint:** Lighthouse + a11y pass, Storybook-style preview page (optional)

## Part 5 - Dashboard & Money Movement UX
- Account overview, charts, recent transactions
- Internal/external transfers, beneficiaries, scheduled transfers (stub)
- CSV statement export
- **Checkpoint:** full money-movement flow from UI with validation states

## Part 6 - Security Hardening
- RBAC (CUSTOMER / ADMIN), rate limiting, audit logging
- Input validation, OWASP headers, CORS lockdown
- Optional 2FA (TOTP) stub
- **Checkpoint:** security review checklist passes, audit log records transfers

## Part 7 - Testing & DevOps
- Backend: JUnit + Testcontainers; Frontend: Vitest + Playwright smoke
- GitHub Actions CI (lint/test/build), Dockerfiles for both apps
- **Checkpoint:** `docker compose up --build` runs full stack, CI green

## Part 8 - Advanced Banking Features
- Savings/loan account types + interest calc job (@Scheduled)
- Virtual cards (tokenized numbers), notifications (email stub / in-app)
- **Checkpoint:** interest accrual job + card issuance demo

## Part 9 - Admin & Ops Panel
- Admin: user search, freeze/unfreeze accounts, transaction review
- Reports: daily totals, statements PDF
- **Checkpoint:** admin can freeze account → transfers blocked with clear error

## Part 10 - Production Polish & Portfolio Finish
- Observability: structured logs, metrics, tracing IDs
- Performance: indexes, N+1 audit, caching (Caffeine/Redis stub)
- Portfolio README: screenshots, architecture diagram, demo seed data, resume bullets
- **Checkpoint:** one-command demo seed + 2-min demo script for interviews

## Conventions (all parts)
- Conventional commits, PR-style checkpoints
- API versioning: /api/v1/...
- Money as NUMERIC(19,4) in DB, BigDecimal in Java, integer minor-units or string in JSON
- Every money mutation writes an audit row
