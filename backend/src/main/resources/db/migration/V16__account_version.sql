-- Optimistic lock on accounts. Every Hibernate update now carries the version
-- and fails loudly when it targets a row that changed since it was read, so a
-- stale write (for example an admin status flip racing a transfer's balance
-- update) can never silently overwrite a newer balance. The ledger's locking
-- reads (SELECT ... FOR UPDATE) are unaffected: a locking read that had to
-- wait re-reads the latest committed row, so it always bumps a fresh version.
ALTER TABLE accounts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
