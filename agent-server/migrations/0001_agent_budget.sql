-- 账户必须由可信应用服务预置；账本不保存提示词、运动摘要或密钥。
CREATE TABLE IF NOT EXISTS agent_budget_accounts (
  owner_id TEXT PRIMARY KEY NOT NULL,
  monthly_limit_micros INTEGER NOT NULL CHECK (
    typeof(monthly_limit_micros) = 'integer' AND monthly_limit_micros BETWEEN 1 AND 1800000
  ),
  halted INTEGER NOT NULL DEFAULT 0 CHECK (halted IN (0, 1))
);

CREATE TABLE IF NOT EXISTS agent_budget_reservations (
  id TEXT PRIMARY KEY NOT NULL,
  owner_id TEXT NOT NULL REFERENCES agent_budget_accounts(owner_id),
  task_id TEXT NOT NULL,
  request_id TEXT NOT NULL,
  period TEXT NOT NULL,
  model TEXT NOT NULL CHECK (model = 'deepseek-flash'),
  rate_version TEXT NOT NULL,
  input_rate INTEGER NOT NULL CHECK (input_rate > 0),
  output_rate INTEGER NOT NULL CHECK (output_rate > 0),
  reserved_micros INTEGER NOT NULL CHECK (reserved_micros > 0),
  state TEXT NOT NULL CHECK (state IN ('reserved', 'uncertain', 'settled', 'released')),
  charged_micros INTEGER CHECK (charged_micros >= 0),
  input_tokens INTEGER CHECK (input_tokens >= 0),
  output_tokens INTEGER CHECK (output_tokens >= 0),
  violation INTEGER NOT NULL DEFAULT 0 CHECK (violation IN (0, 1)),
  created_at_millis INTEGER NOT NULL,
  UNIQUE (owner_id, request_id),
  CHECK (
    (state = 'settled' AND charged_micros IS NOT NULL AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL)
    OR (state <> 'settled' AND charged_micros IS NULL AND input_tokens IS NULL AND output_tokens IS NULL)
  )
);

CREATE INDEX IF NOT EXISTS agent_budget_owner_period
ON agent_budget_reservations (owner_id, period);
