-- 多 Agent 交接：任务当前归属与交接次数
ALTER TABLE task ADD COLUMN owner_agent    TEXT;
ALTER TABLE task ADD COLUMN handoff_count  BIGINT NOT NULL DEFAULT 0;
