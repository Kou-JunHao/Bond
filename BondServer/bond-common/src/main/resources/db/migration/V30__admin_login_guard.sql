-- V30：管理端登录审计日志 + fail2ban 式封禁（task-144）
--
-- 说明：
-- 1) admin_operation_logs（V3）记录“管理员操作”，本迁移记录“管理后台登录”事件，两者独立。
-- 2) admin_login_logs 只记录被判定为“管理端登录”的尝试（X-Client-App: bond-admin 或目标账号 is_admin=true）。
-- 3) admin_login_blocks 持久化封禁状态（重启不丢）；唯一索引只约束“未解除”的封禁行，
--    过期封禁行在新建封禁前会先被 lifted_at 标记（避免同一 scope+value 出现两条 active 行）。
-- 4) 默认策略走 system_config，后台可通过 /api/admin/login-guard 调整。

CREATE TABLE admin_login_logs (
    id          BIGINT PRIMARY KEY,
    user_id     BIGINT NULL REFERENCES users (id) ON DELETE SET NULL,
    username    TEXT NOT NULL,
    ip          VARCHAR(64) NOT NULL,
    user_agent  VARCHAR(512) NULL,
    outcome     VARCHAR(16) NOT NULL,
    reason      VARCHAR(256) NULL,
    attempt_no  INT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE admin_login_logs IS '管理端登录审计日志（task-144）';
COMMENT ON COLUMN admin_login_logs.outcome IS 'success/bad_credentials/user_disabled/blocked/captcha_required/error';
COMMENT ON COLUMN admin_login_logs.attempt_no IS '同一 IP+username 自上次成功以来的连续失败序号';

CREATE INDEX ix_admin_login_logs_created ON admin_login_logs (created_at DESC);
CREATE INDEX ix_admin_login_logs_ip ON admin_login_logs (ip, created_at DESC);
CREATE INDEX ix_admin_login_logs_username ON admin_login_logs (username, created_at DESC);
CREATE INDEX ix_admin_login_logs_outcome ON admin_login_logs (outcome, created_at DESC);

CREATE TABLE admin_login_blocks (
    id          BIGINT PRIMARY KEY,
    scope       VARCHAR(8) NOT NULL,
    value       VARCHAR(128) NOT NULL,
    reason      VARCHAR(256) NULL,
    fail_count  INT NOT NULL DEFAULT 0,
    banned_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    lifted_at   TIMESTAMPTZ NULL,
    lifted_by   BIGINT NULL REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_admin_login_blocks_scope CHECK (scope IN ('ip', 'user'))
);

COMMENT ON TABLE admin_login_blocks IS '管理端登录 fail2ban 封禁（持久化，重启不丢）';
COMMENT ON COLUMN admin_login_blocks.scope IS 'ip=按客户端 IP 封禁；user=按登录账号封禁';
COMMENT ON COLUMN admin_login_blocks.expires_at IS '封禁到期时间；到期后下次新建封禁时会把该行标记为已解除';

CREATE UNIQUE INDEX uq_admin_login_blocks_active ON admin_login_blocks (scope, value) WHERE lifted_at IS NULL;
CREATE INDEX ix_admin_login_blocks_expires ON admin_login_blocks (expires_at DESC);
CREATE INDEX ix_admin_login_blocks_lookup ON admin_login_blocks (scope, value, banned_at DESC);

INSERT INTO system_config (config_key, config_value, config_type, description) VALUES
    ('admin_guard.enabled', 'true', 'boolean', '管理端登录 fail2ban 防护开关（默认开启）'),
    ('admin_guard.fail_window_seconds', '600', 'int', '失败计数窗口（秒，默认 600）'),
    ('admin_guard.fail_threshold_ip', '5', 'int', '窗口内同一 IP 失败次数阈值（默认 5，达到即封禁）'),
    ('admin_guard.fail_threshold_user', '10', 'int', '窗口内同一账号失败次数阈值（默认 10，达到即封禁）'),
    ('admin_guard.ban_seconds', '900', 'int', '封禁时长基数（秒，默认 900，指数退避 900→1800→3600，上限 86400）'),
    ('admin_guard.ip_allowlist', '', 'string', '永不封禁的 IP 白名单（逗号分隔；127.0.0.1/::1 始终放行）')
ON CONFLICT (config_key) DO NOTHING;
