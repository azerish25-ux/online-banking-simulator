-- Part 5: saved beneficiaries (transfer address book).
CREATE TABLE IF NOT EXISTS beneficiaries (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id),
  nickname VARCHAR(80) NOT NULL,
  iban VARCHAR(34) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, iban)
);
CREATE INDEX IF NOT EXISTS idx_beneficiaries_user ON beneficiaries(user_id);
