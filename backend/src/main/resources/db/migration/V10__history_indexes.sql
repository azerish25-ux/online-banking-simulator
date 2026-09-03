-- Part E: composite history indexes for the UNION ALL access path.
CREATE INDEX IF NOT EXISTS idx_tx_from_created ON transactions(from_account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tx_to_created ON transactions(to_account_id, created_at DESC);
