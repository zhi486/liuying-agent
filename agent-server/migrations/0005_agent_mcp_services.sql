-- 仅保存插件元数据、目录与用户批准；不保存第三方凭据或工具结果。
CREATE TABLE IF NOT EXISTS agent_mcp_services (
  id TEXT PRIMARY KEY NOT NULL CHECK (length(id) = 36),
  owner_id TEXT NOT NULL REFERENCES agent_budget_accounts(owner_id),
  endpoint TEXT NOT NULL CHECK (length(endpoint) <= 2048),
  revision TEXT NOT NULL CHECK (length(revision) = 36),
  enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
  catalog_json TEXT CHECK (catalog_json IS NULL OR
    (json_valid(catalog_json) AND length(CAST(catalog_json AS BLOB)) <= 131072)),
  catalog_fingerprint TEXT CHECK (catalog_fingerprint IS NULL OR length(catalog_fingerprint) = 64),
  grants_json TEXT NOT NULL CHECK (json_valid(grants_json) AND length(CAST(grants_json AS BLOB)) <= 16384),
  CHECK ((catalog_json IS NULL) = (catalog_fingerprint IS NULL)),
  CHECK (enabled = 0 OR catalog_json IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS agent_mcp_owner ON agent_mcp_services(owner_id, id);
