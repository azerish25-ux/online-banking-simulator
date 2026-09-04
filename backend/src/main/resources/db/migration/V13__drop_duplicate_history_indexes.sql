-- V1 created idx_tx_from / idx_tx_to on (from_account_id|to_account_id, created_at)
-- and V10 re-created the identical key sets as idx_tx_from_created / idx_tx_to_created
-- for the UNION ALL history access path. Keeping both pairs makes every insert
-- and update write the same key set twice. Retire the V1 names; the V10 pair
-- stays (they are the ones the history queries and their plans reference).
DROP INDEX IF EXISTS idx_tx_from;
DROP INDEX IF EXISTS idx_tx_to;
