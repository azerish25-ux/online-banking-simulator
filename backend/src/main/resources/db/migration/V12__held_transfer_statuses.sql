-- Large transfers are held for operator review instead of settling instantly.
-- HELD: created, awaiting an ops decision (no money has moved yet).
-- CANCELLED: declined by ops (nothing to reverse -- no money moved).
-- The constraint is explicitly named (V8), so it can be dropped portably on
-- PostgreSQL and H2.
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_status_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_status_check
  CHECK (status IN ('POSTED', 'HELD', 'CANCELLED'));
