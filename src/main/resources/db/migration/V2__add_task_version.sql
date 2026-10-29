-- 任务版本号：每写入一条状态 +1
-- 用途：写入状态项时记进 state_item.version（见 StateItem.version 注释），
-- 后续乐观锁（防并发写冲突）、状态卡按版本回放都以此为准。
ALTER TABLE task ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
