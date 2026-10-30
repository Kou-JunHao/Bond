-- V15：系统公告 + 公告已读 + 管理操作审计日志（任务单 #19）
--
-- 设计要点：
--  * announcements 为唯一来源，published_at 决定可见时间（未来时间 = 定时发布，不展示），
--    expires_at 为空 = 永不过期；level 限定 info/warn/critical（CHECK 与 Java 双保险）。
--  * announcement_reads 记录「谁读过了哪条公告」，唯一约束保证幂等；不做已读回执列表。
--  * audit_logs 只追加（append-only）：不提供 DELETE 接口，归属人字段冗余存 actor_name，
--    避免用户改名/删除后审计记录失真。历史（V15 之前）的操作没有记录，属预期。
CREATE TABLE IF NOT EXISTS announcements (
    id           BIGINT       PRIMARY KEY,
    title        VARCHAR(200) NOT NULL,
    content      TEXT         NOT NULL,
    level        VARCHAR(16)  NOT NULL DEFAULT 'info',
    published_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ,
    created_by   BIGINT       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_announcements_level CHECK (level IN ('info', 'warn', 'critical'))
);

CREATE INDEX IF NOT EXISTS ix_announcements_published
    ON announcements (published_at DESC) WHERE published_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_announcements_expires
    ON announcements (expires_at);

CREATE TABLE IF NOT EXISTS announcement_reads (
    id              BIGINT      PRIMARY KEY,
    announcement_id BIGINT      NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
    user_id         BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_announcement_reads UNIQUE (announcement_id, user_id)
);

CREATE INDEX IF NOT EXISTS ix_announcement_reads_user ON announcement_reads (user_id);

CREATE TABLE IF NOT EXISTS audit_logs (
    id          BIGINT      PRIMARY KEY,
    actor_id    BIGINT      NOT NULL,
    actor_name  VARCHAR(64),
    action      VARCHAR(64) NOT NULL,
    target_type VARCHAR(32),
    target_id   BIGINT,
    detail      JSONB,
    ip          VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_audit_logs_created ON audit_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_logs_actor ON audit_logs (actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_logs_action ON audit_logs (action, created_at DESC);

-- 公告 updated_at 复用 V1 的 set_updated_at()
DROP TRIGGER IF EXISTS trg_announcements_set_updated_at ON announcements;
CREATE TRIGGER trg_announcements_set_updated_at
    BEFORE UPDATE ON announcements
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
