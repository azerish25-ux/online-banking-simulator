-- Part 9: ops review workflow on transfers.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS flagged BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS reviewed BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_tx_flagged ON transactions(flagged, reviewed, created_at DESC);
