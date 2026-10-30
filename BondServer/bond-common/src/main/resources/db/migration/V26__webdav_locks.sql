-- WebDAV 真实锁（task-126）。
--
-- 背景：MVP 阶段 LOCK 返回 200 但不落任何状态，客户端（Office/Windows Explorer）
-- 以为拿到排他锁，实际并发写会静默互相覆盖。这里引入最小可用的持久锁：
--   * 一个资源同一时刻最多一个锁（resource_path 唯一），过期后清理；
--   * depth 只支持 0 / infinity；timeout 由 LOCK 请求的 Timeout 头决定（上限 24h）；
--   * 只做排他写锁（exclusive write），不做共享锁——与 DAV: 1,2 中 class 2 的
--     最小承诺一致，文档 docs/security-verify.md 里同步说明边界。
CREATE TABLE IF NOT EXISTS webdav_locks (
    id BIGINT PRIMARY KEY,
    token VARCHAR(128) NOT NULL,
    resource_path VARCHAR(1024) NOT NULL,
    owner TEXT,
    depth VARCHAR(16) NOT NULL DEFAULT 'infinity',
    timeout_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_webdav_locks_token
    ON webdav_locks (token);

CREATE UNIQUE INDEX IF NOT EXISTS uq_webdav_locks_resource_path
    ON webdav_locks (resource_path);

CREATE INDEX IF NOT EXISTS idx_webdav_locks_timeout
    ON webdav_locks (timeout_at);
