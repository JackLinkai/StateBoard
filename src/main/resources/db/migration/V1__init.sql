-- 任务
CREATE TABLE task (
    task_id      TEXT PRIMARY KEY,
    description  TEXT,
    status       TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

-- 状态项：四类状态共用一张表，用 type 区分
CREATE TABLE state_item (
    id           TEXT PRIMARY KEY,
    task_id      TEXT NOT NULL REFERENCES task(task_id),
    type         TEXT NOT NULL,        -- CONSTRAINT | FACT | TODO | INVALIDATED
    payload      JSONB NOT NULL,       -- 各类型自己的字段序列化进来
    source       TEXT NOT NULL,
    version      BIGINT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_state_item_task_version ON state_item (task_id, version);

-- 状态卡版本
CREATE TABLE state_card_version (
    task_id          TEXT NOT NULL REFERENCES task(task_id),
    version          BIGINT NOT NULL,
    card             JSONB NOT NULL,
    estimated_tokens INT NOT NULL,
    compiled_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (task_id, version)
);