-- F04: separate the moment money actually moved (posted_at) from the moment
-- the instruction was recorded (created_at). A review-threshold transfer is
-- REQUESTED when its HELD row is created but only POSTS when an operator
-- approves it - possibly in a later month - so monthly summaries, statements,
-- daily totals and public stats must bucket and cut on posted_at, never on
-- creation time. HELD and CANCELLED rows never moved money and keep
-- posted_at NULL; legacy POSTED rows (which predate the distinction) are
-- backfilled to their created_at, the only evidence they carry.

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS posted_at TIMESTAMP WITH TIME ZONE;

UPDATE transactions SET posted_at = created_at
WHERE status = 'POSTED' AND posted_at IS NULL;

-- Every row that claims money moved must be able to say when. Named, so a
-- future policy change can drop it portably on PostgreSQL and H2.
ALTER TABLE transactions ADD CONSTRAINT transactions_posted_time_check
  CHECK (status <> 'POSTED' OR posted_at IS NOT NULL);

-- Reporting reads now range over posted_at per account (statements,
-- summaries); keep them indexable like the created_at history reads.
CREATE INDEX IF NOT EXISTS idx_tx_posted_from ON transactions(from_account_id, posted_at DESC);
CREATE INDEX IF NOT EXISTS idx_tx_posted_to ON transactions(to_account_id, posted_at DESC);
