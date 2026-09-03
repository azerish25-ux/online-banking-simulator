-- Part B: domain constraints, transaction kinds, update timestamps.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'TRANSFER';
UPDATE transactions SET kind = 'DEPOSIT' WHERE from_account_id IS NULL AND memo LIKE 'Simulated deposit%';
UPDATE transactions SET kind = 'INTEREST' WHERE memo LIKE '%interest%';
ALTER TABLE transactions ALTER COLUMN kind DROP DEFAULT;

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE cards ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('CUSTOMER', 'ADMIN'));
ALTER TABLE accounts ADD CONSTRAINT accounts_type_check CHECK (type IN ('CHECKING', 'SAVINGS', 'LOAN'));
ALTER TABLE accounts ADD CONSTRAINT accounts_status_check CHECK (status IN ('ACTIVE', 'FROZEN'));
ALTER TABLE transactions ADD CONSTRAINT transactions_status_check CHECK (status IN ('POSTED'));
ALTER TABLE transactions ADD CONSTRAINT transactions_kind_check CHECK (kind IN ('TRANSFER', 'DEPOSIT', 'INTEREST'));
ALTER TABLE cards ADD CONSTRAINT cards_status_check CHECK (status IN ('ACTIVE', 'FROZEN'));

CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_logs(action, id DESC);
ALTER TABLE cards ADD CONSTRAINT cards_pan_unique UNIQUE (pan_hash);
