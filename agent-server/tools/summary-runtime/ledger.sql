-- 仅隔离测试加载，不属于生产迁移目录。
CREATE TABLE summary_usage (
  owner_id TEXT NOT NULL, device_id TEXT NOT NULL, request_id TEXT NOT NULL,
  input_hash TEXT NOT NULL, input_rate INTEGER NOT NULL CHECK(input_rate > 0),
  output_rate INTEGER NOT NULL CHECK(output_rate > 0),
  input_tokens INTEGER, output_tokens INTEGER,
  conflict INTEGER NOT NULL DEFAULT 0 CHECK(conflict IN (0,1)),
  PRIMARY KEY(owner_id, device_id, request_id),
  CHECK ((input_tokens IS NULL AND output_tokens IS NULL) OR
    (typeof(input_tokens) = 'integer' AND input_tokens >= 0 AND
     typeof(output_tokens) = 'integer' AND output_tokens >= 0))
) STRICT;
