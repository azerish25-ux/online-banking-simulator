-- F25: commit-safe external delivery. Email was previously sent INSIDE the
-- money/notification transaction - a slow or failing provider held the
-- ledger open, and a rollback could still have "sent" mail for an operation
-- that never happened. Now every event that should produce mail commits an
-- OUTBOX INTENT in the same transaction as the operation (in-app
-- notifications stay in `notifications`; external delivery is tracked here).
-- A worker claims due rows after commit, calls the (stubbed) provider, and
-- records the outcome. `delivery_key` is the idempotent delivery identity:
-- the unique index makes a re-enqueued event insert once, and the worker
-- claims each row exactly once, so concurrent runs never double-send.
-- Failures retry with backoff up to `attempts` and then dead-letter to
-- FAILED, where an operator sees the row and can requeue it.
CREATE TABLE IF NOT EXISTS email_outbox (
  id               UUID PRIMARY KEY,
  user_id          UUID NOT NULL,
  email            VARCHAR(320) NOT NULL,
  notification_id  UUID,
  delivery_key     VARCHAR(255) NOT NULL,
  subject          VARCHAR(120) NOT NULL,
  body             VARCHAR(500) NOT NULL,
  status           VARCHAR(16)  NOT NULL,           -- PENDING | DELIVERING | SENT | FAILED
  attempts         INTEGER      NOT NULL,
  next_attempt_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  last_error       VARCHAR(255),
  created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_email_outbox_delivery_key
  ON email_outbox (delivery_key);
CREATE INDEX IF NOT EXISTS idx_email_outbox_due
  ON email_outbox (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_email_outbox_delivering
  ON email_outbox (status, updated_at);
