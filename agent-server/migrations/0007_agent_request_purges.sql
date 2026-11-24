-- 只保存原身份及请求键；未知请求也必须阻止迟到提交，不保存任何聊天正文。
CREATE TABLE IF NOT EXISTS agent_request_purges (
  owner_id TEXT NOT NULL REFERENCES agent_budget_accounts(owner_id),
  client_request_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  purged_at INTEGER NOT NULL,
  PRIMARY KEY (owner_id, client_request_id),
  FOREIGN KEY (device_id, owner_id) REFERENCES agent_devices(id, owner_id)
);
