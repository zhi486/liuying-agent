-- 仅隔离验收库使用，不进入正式迁移。
CREATE TABLE verify_limits (
  singleton INTEGER PRIMARY KEY CHECK (singleton=1),
  started_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL CHECK (expires_at>=0 AND expires_at-started_at<=1800000),
  requests INTEGER NOT NULL CHECK (requests BETWEEN 0 AND 100)
);
CREATE TABLE verify_admissions (
  owner TEXT NOT NULL,
  request_id TEXT NOT NULL,
  PRIMARY KEY (owner,request_id)
);
CREATE TABLE verify_transport (
  task_id TEXT NOT NULL,
  round INTEGER NOT NULL CHECK (round BETWEEN 1 AND 3),
  PRIMARY KEY(task_id,round)
);
