-- 旧预留保持空执行关联，不把历史未知费用当作未发送证明。
ALTER TABLE agent_budget_reservations ADD COLUMN execution_hash TEXT
  CHECK (execution_hash IS NULL OR (length(execution_hash) = 64 AND execution_hash NOT GLOB '*[^0-9a-f]*'));
ALTER TABLE agent_budget_reservations ADD COLUMN dispatch_state TEXT
  CHECK (dispatch_state IS NULL OR dispatch_state IN ('prepared','may_have_dispatched','closed_not_dispatched'));
ALTER TABLE agent_budget_reservations ADD COLUMN rate_expires_at_millis INTEGER;

CREATE TABLE agent_usage_receipts (
  reservation_id TEXT PRIMARY KEY NOT NULL REFERENCES agent_budget_reservations(id),
  input_tokens INTEGER NOT NULL CHECK (typeof(input_tokens) = 'integer' AND input_tokens BETWEEN 0 AND 9007199254740991),
  output_tokens INTEGER NOT NULL CHECK (typeof(output_tokens) = 'integer' AND output_tokens BETWEEN 0 AND 9007199254740991),
  received_at_millis INTEGER NOT NULL,
  applied INTEGER NOT NULL DEFAULT 0 CHECK (applied IN (0,1))
);
