-- 仅扩展个人设备和云任务，不改写第一批预算数据。
CREATE TABLE IF NOT EXISTS agent_devices (
  id TEXT PRIMARY KEY NOT NULL,
  owner_id TEXT NOT NULL REFERENCES agent_budget_accounts(owner_id),
  token_hash TEXT NOT NULL UNIQUE CHECK (length(token_hash) = 64),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL CHECK (expires_at = created_at + 7776000000),
  revoked_at INTEGER,
  UNIQUE (id, owner_id)
);

CREATE TABLE IF NOT EXISTS agent_cloud_tasks (
  id TEXT PRIMARY KEY NOT NULL,
  owner_id TEXT NOT NULL REFERENCES agent_budget_accounts(owner_id),
  device_id TEXT NOT NULL,
  client_request_id TEXT NOT NULL,
  payload_digest TEXT CHECK (payload_digest IS NULL OR length(payload_digest) = 64),
  payload_expires_at INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('queued','running','completed','failed','blocked','cancelled','timed_out')),
  reason TEXT,
  accepted_at INTEGER NOT NULL,
  deadline_at INTEGER NOT NULL CHECK (deadline_at = accepted_at + 30000),
  updated_at INTEGER NOT NULL CHECK (updated_at >= accepted_at),
  execution_hash TEXT CHECK (execution_hash IS NULL OR length(execution_hash) = 64),
  result_json TEXT CHECK (result_json IS NULL OR json_valid(result_json)),
  result_expires_at INTEGER,
  result_state TEXT NOT NULL DEFAULT 'none' CHECK (result_state IN ('none','available','acknowledged','expired','withdrawn')),
  FOREIGN KEY (device_id, owner_id) REFERENCES agent_devices(id, owner_id),
  UNIQUE (owner_id, client_request_id),
  CHECK (COALESCE((status IN ('queued','running','completed') AND reason IS NULL)
    OR (status = 'cancelled' AND reason IN ('cancelled','device_revoked','consent_revoked'))
    OR (status = 'timed_out' AND reason = 'deadline')
    OR (status = 'failed' AND reason IN ('invalid_task','invalid_model_output','budget_unavailable','model_unavailable','usage_invalid','usage_limit'))
    OR (status = 'blocked' AND reason IN ('consent_required','scope_denied','duplicate_tool','tool_limit','step_limit','budget_denied','invalid_tool')), 0)),
  CHECK (status <> 'running' OR execution_hash IS NOT NULL),
  CHECK (COALESCE((result_state = 'available' AND result_json IS NOT NULL AND result_expires_at IS NOT NULL
      AND status NOT IN ('queued','running') AND json_extract(result_json, '$.status') = status)
    OR (result_state <> 'available' AND result_json IS NULL), 0))
);

CREATE UNIQUE INDEX IF NOT EXISTS agent_cloud_one_active
ON agent_cloud_tasks(owner_id) WHERE status IN ('queued','running');
CREATE INDEX IF NOT EXISTS agent_cloud_device ON agent_cloud_tasks(device_id, status);
