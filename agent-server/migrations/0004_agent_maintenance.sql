-- 仅保存维护游标；不改写账户、费用或任务记录。版本防止迟到调用回退进度。
CREATE TABLE IF NOT EXISTS agent_maintenance_state (
  id TEXT PRIMARY KEY NOT NULL CHECK (id = 'execution-recovery'),
  cursor TEXT NOT NULL DEFAULT '' CHECK (length(cursor) <= 80 AND cursor NOT GLOB '*[^a-zA-Z0-9_-]*'),
  revision INTEGER NOT NULL DEFAULT 0 CHECK (typeof(revision) = 'integer' AND revision >= 0)
);
INSERT INTO agent_maintenance_state (id) VALUES ('execution-recovery') ON CONFLICT(id) DO NOTHING;
