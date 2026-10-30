-- v0.6 多租户：task 表增加 tenant_id，存量任务归入缺省租户。
ALTER TABLE task ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default';

CREATE INDEX idx_task_tenant ON task (tenant_id, updated_at DESC);
