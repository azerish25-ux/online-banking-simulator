-- Deterministic total order for transaction listings. Two rows created in the
-- same instant share created_at (rows persisted in one flush get identical
-- timestamps), and the UUID primary key is random, so neither can break ties in
-- insertion order. seq is a monotonic identity assigned by the database on
-- insert: listings order by (created_at DESC, seq DESC) so rows tied on time
-- resolve newest-inserted-first, which keeps pages stable and never duplicates
-- or skips a row across a page boundary.
ALTER TABLE transactions ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
